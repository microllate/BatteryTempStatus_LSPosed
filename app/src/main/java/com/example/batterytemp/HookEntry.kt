package com.example.batterytemp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
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
                                        ?: return@afterHook

                                    if (statusBarView.getTag(TAG_KEY) != null) return@afterHook

                                    val leftSideGroup = statusBarView.findViewById<ViewGroup>(0x7f0a0884)
                                        ?: return@afterHook

                                    val tempView = TextView(statusBarView.context).apply {
                                        setTag(TAG_KEY, true)
                                        setPadding(0, 0, 10, 0)
                                    }

                                    updateTextColorAndSize(statusBarView, tempView)
                                    leftSideGroup.addView(tempView, 1)
                                    statusBarView.setTag(TAG_KEY, true)
                                    injectedTextView = tempView

                                    startTemperatureUpdater(statusBarView, tempView)
                                } catch (e: Throwable) {
                                    loggerD(msg = "BatteryTemp: controller injection failed: ${e.stackTraceToString()}")
                                }
                            }
                        }
                    }

                    val networkSpeedViewData = bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher {
                            className("NetworkSpeedView", StringMatchType.Contains)
                        }
                        findFirst = true
                    }.firstOrNull()

                    if (networkSpeedViewData == null) {
                        loggerD(msg = "BatteryTemp: NetworkSpeedView not found")
                        return@use
                    }

                    val networkSpeedViewClass = networkSpeedViewData.getInstance(classLoader)
                    loggerD(msg = "BatteryTemp: current NetworkSpeedView = ${networkSpeedViewClass.name}")

                    // Restrict the hook to NetworkSpeedView. If setTextColor is inherited,
                    // superClass() makes YukiHookAPI resolve that inherited method on this class.
                    // No global TextView hook and no ViewParent traversal are used.
                    findClass(networkSpeedViewClass.name).hook {
                        injectMember {
                            method {
                                name = "setTextColor"
                                param(Int::class.javaPrimitiveType!!)
                                superClass(isOnlySuperClass = false)
                            }
                            afterHook {
                                try {
                                    injectedTextView?.setTextColor(args(0).int())
                                } catch (e: Throwable) {
                                    loggerD(msg = "BatteryTemp: network color sync failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                loggerD(msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}")
            }
        }
    }

    companion object {
        private const val TAG_KEY = 0x42545431
        private const val REFRESH_MS = 2000L

        @Volatile
        private var injectedTextView: TextView? = null

        private fun startTemperatureUpdater(parent: ViewGroup, target: TextView) {
            val handler = Handler(Looper.getMainLooper())
            val update = object : Runnable {
                override fun run() {
                    try {
                        val intent = parent.context.registerReceiver(
                            null,
                            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        )
                        if (intent != null) {
                            val tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                            val celsius = Math.round(tempTenth / 10.0f)
                            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                            val batteryManager =
                                parent.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                            val current = batteryManager.getIntProperty(2)
                            val power = voltage.toFloat() * current.toFloat() / 1_000_000_000.0f

                            val powerString: String = if (power < 0) {
                                String.format(Locale.getDefault(), " %.2fw", power)
                            } else {
                                String.format(Locale.getDefault(), " %.2fw", power)
                            }

                            target.text = String.format(
                                Locale.getDefault(),
                                " %s℃ %s",
                                celsius,
                                powerString
                            )
                        }
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: temperature/power update failed: ${e.message}")
                    }
                    handler.postDelayed(this, REFRESH_MS)
                }
            }
            handler.post(update)
        }

        private fun updateTextColorAndSize(parent: ViewGroup, targetTextView: TextView) {
            val clockView = parent.findViewById<TextView>(0x7f0a024f)
            if (clockView != null) {
                targetTextView.setTextColor(clockView.currentTextColor)
                val fontSize = clockView.textSize /
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
    }
}
