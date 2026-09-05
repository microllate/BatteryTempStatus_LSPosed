package com.example.batterytemp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.util.Locale

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs { isDebug = true }

    override fun onHook() = encase {
        loadApp(name = "com.android.systemui") {
            try {
                val classLoader = appClassLoader ?: run {
                    loggerD(msg = "BatteryTemp: SystemUI classLoader is null")
                    return@loadApp
                }

                val apkPath = appInfo.sourceDir
                loggerD(msg = "BatteryTemp: DexKit scanning current SystemUI: $apkPath")
                System.loadLibrary("dexkit")

                DexKitBridge.create(apkPath).use { bridge ->
                    val controllerData = bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher {
                            className("PhoneStatusBarViewController", StringMatchType.Contains)
                            methods {
                                add {
                                    name("onViewAttached")
                                    returnType("void")
                                    paramCount(0)
                                }
                            }
                        }
                        findFirst = true
                    }.firstOrNull()

                    if (controllerData == null) {
                        loggerD(msg = "BatteryTemp: PhoneStatusBarViewController not found")
                        return@use
                    }

                    val controllerClass = controllerData.getInstance(classLoader)
                    loggerD(msg = "BatteryTemp: current hook target = ${controllerClass.name}")

                    // Keep the original behavior: initialize the injected TextView from the clock,
                    // then let NetworkSpeedView's setTextColor updates override its color.
                    findClass(controllerClass.name).hook {
                        injectMember {
                            method {
                                name = "onViewAttached"
                                emptyParam()
                            }
                            afterHook {
                                try {
                                    val controller = instanceOrNull ?: return@afterHook
                                    val statusBarView = readField(controller, "mView") as? ViewGroup
                                    if (statusBarView == null) {
                                        loggerD(msg = "BatteryTemp: PhoneStatusBarViewController.mView is null/not ViewGroup")
                                        return@afterHook
                                    }

                                    if (statusBarView.getTag(TAG_KEY) != null) return@afterHook

                                    // Keep the current source's fixed resource id and insertion index.
                                    val leftSideGroup = statusBarView.findViewById<ViewGroup>(0x7f0a0884)
                                    if (leftSideGroup == null) {
                                        loggerD(msg = "BatteryTemp: left side container 0x7f0a0884 not found")
                                        return@afterHook
                                    }

                                    val context = statusBarView.context
                                    val tempView = TextView(context).apply {
                                        setTag(TAG_KEY, true)
                                        setPadding(0, 0, 10, 0)
                                    }

                                    // Original initialization: color and size follow the clock.
                                    updateTextColorAndSize(statusBarView, tempView)

                                    // Original insertion behavior: index 1, without changing the index semantics.
                                    leftSideGroup.addView(tempView, 1)
                                    statusBarView.setTag(TAG_KEY, true)

                                    loggerD(
                                        msg = "BatteryTemp: injected using PhoneStatusBarViewController into " +
                                            "${leftSideGroup.javaClass.name} id=${resourceName(leftSideGroup)}"
                                    )

                                    startTemperatureUpdater(statusBarView, tempView)
                                } catch (e: Throwable) {
                                    loggerD(msg = "BatteryTemp: controller injection failed: ${e.stackTraceToString()}")
                                }
                            }
                        }
                    }

                    // Original behavior: hook every TextView.setTextColor(int). If the TextView is
                    // inside NetworkSpeedView, copy that color to the injected temperature TextView.
                    val networkSpeedViewClass = try {
                        findClass("com.android.systemui.statusbar.views.NetworkSpeedView").get()
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: NetworkSpeedView class not found: ${e.message}")
                        null
                    }

                    if (networkSpeedViewClass != null) {
                        findClass(TextView::class.java.name).hook {
                            injectMember {
                                method {
                                    name = "setTextColor"
                                    param(Int::class.javaPrimitiveType!!)
                                }
                                afterHook {
                                    try {
                                        val target = instanceOrNull as? TextView ?: return@afterHook
                                        val injected = findInjectedTextView(target)
                                        if (injected == null || target === injected) return@afterHook
                                        if (isInsideNetworkSpeedView(target, networkSpeedViewClass)) {
                                            val color = args(0).int()
                                            injected.setTextColor(color)
                                        }
                                    } catch (e: Throwable) {
                                        loggerD(msg = "BatteryTemp: network color sync failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    loggerD(msg = "BatteryTemp: current SystemUI controller hook initialized")
                }
            } catch (e: Throwable) {
                loggerD(msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}")
            }
        }
    }

    companion object {
        private const val TAG_KEY = 0x42545431
        private const val REFRESH_MS = 2000L

        private fun findInjectedTextView(view: View): TextView? {
            var parent: ViewParent? = view
            while (parent != null) {
                if (parent is ViewGroup) {
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        if (child is TextView && child.getTag(TAG_KEY) == true) return child
                    }
                }
                if (parent is View) parent = parent.parent else break
            }
            return null
        }

        private fun isInsideNetworkSpeedView(view: View, networkSpeedViewClass: Class<*>): Boolean {
            var parent: ViewParent? = view.parent
            while (parent != null) {
                if (networkSpeedViewClass.isInstance(parent)) return true
                if (parent !is View) break
                parent = parent.parent
            }
            return false
        }

        private fun startTemperatureUpdater(parent: ViewGroup, target: TextView) {
            val handler = Handler(Looper.getMainLooper())
            val update = object : Runnable {
                override fun run() {
                    try {
                        if (target != null && parent.context != null) {
                            val intent = parent.context.registerReceiver(
                                null,
                                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            )

                            if (intent != null) {
                                val tempTenth = intent.getIntExtra(
                                    BatteryManager.EXTRA_TEMPERATURE,
                                    0
                                )
                                val celsius = Math.round(tempTenth / 10.0f)

                                val voltage = intent.getIntExtra(
                                    BatteryManager.EXTRA_VOLTAGE,
                                    -1
                                )

                                val batteryManager =
                                    parent.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

                                val current = batteryManager.getIntProperty(2)

                                val power =
                                    voltage.toFloat() * current.toFloat() / 1_000_000_000.0f

                                val powerString: String = if (power < 0) {
                                    String.format(Locale.getDefault(), " %.2fw", power)
                                } else {
                                    String.format(Locale.getDefault(), " %.2fw", power)
                                }

                                val tempString = String.format(
                                    Locale.getDefault(),
                                    " %s℃ %s",
                                    celsius,
                                    powerString
                                )
                                target.text = tempString
                            }
                        }
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: temperature/power update failed: ${e.message}")
                    }

                    // Keep the original updater lifecycle: always schedule the next update.
                    handler.postDelayed(this, REFRESH_MS)
                }
            }
            handler.post(update)
        }

        private fun updateTextColorAndSize(parent: ViewGroup, targetTextView: TextView) {
            val clockView = parent.findViewById<TextView>(0x7f0a024f)
            if (clockView != null) {
                targetTextView.setTextColor(clockView.currentTextColor)
                val fontSize =
                    clockView.textSize /
                        parent.context.resources.displayMetrics.scaledDensity
                targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            } else {
                targetTextView.setTextColor(0xFFFFFFFF.toInt())
                targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        }

        private fun readField(instance: Any, fieldName: String): Any? {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try {
                    val field: Field = type.getDeclaredField(fieldName)
                    field.isAccessible = true
                    return field.get(instance)
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                }
            }
            return null
        }

        private fun resourceName(view: View): String = try {
            if (view.id == View.NO_ID) "NO_ID" else view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            "id=${view.id}"
        }
    }
}
