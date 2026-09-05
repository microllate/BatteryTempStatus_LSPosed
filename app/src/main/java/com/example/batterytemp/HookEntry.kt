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
import com.highcapable.yukihookapi.hook.factory.findClass
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.highcapable.yukihookapi.hook.xposed.proxy.YukiHookModuleApp
import com.highcapable.yukihookapi.hook.xposed.bridge.data.YukiHookBridgeData
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.factory.injectMember
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.type.android.TextView

class HookEntry : YukiHookModuleApp() {

    override fun onCreate() {
        super.onCreate()
        if (packageName != "com.android.systemui") return

        val classLoader = classLoader
        try {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarViewController"
            )

            controllerClass.findClass().hook {
                injectMember {
                    method {
                        name = "onViewAttached"
                        emptyParam()
                    }
                    afterHook {
                        val handler = Handler(Looper.getMainLooper())
                        val controllerView = instanceOrNull?.let {
                            try {
                                it.javaClass.getDeclaredField("mView").apply { isAccessible = true }.get(it)
                            } catch (_: Throwable) {
                                null
                            }
                        }

                        if (controllerView !is ViewGroup) return@afterHook

                        val statusBarView = controllerView
                        val systemUiContext = statusBarView.context
                        val leftSideGroup = try {
                            statusBarView.findViewById<ViewGroup>(0x7f0a0884)
                        } catch (_: Throwable) {
                            null
                        }

                        if (leftSideGroup == null) return@afterHook

                        val tempTextView = TextView(systemUiContext).apply {
                            setTag(TAG_KEY, true)
                            setPadding(0, 0, 10, 0)
                        }

                        updateTextColorAndSize(statusBarView, tempTextView, systemUiContext)
                        leftSideGroup.addView(tempTextView, 1)
                        startTempUpdate(handler, tempTextView, systemUiContext)
                    }
                }
            }

            val networkSpeedViewClass = classLoader.loadClass(
                "com.android.systemui.statusbar.views.NetworkSpeedView"
            )

            TextView::class.java.findClass().hook {
                injectMember {
                    method {
                        name = "setTextColor"
                        param(Int::class.javaPrimitiveType!!)
                    }
                    afterHook {
                        val target = instanceOrNull as? TextView ?: return@afterHook
                        val injected = findInjectedTextView(target) ?: return@afterHook
                        if (target === injected) return@afterHook

                        if (isInsideNetworkSpeedView(target, networkSpeedViewClass)) {
                            injected.setTextColor(args(0).int())
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            loggerD(msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}")
        }
    }

    companion object {
        private const val TAG_KEY = 0x42545431
        private const val REFRESH_MS = 2000L

        private fun findInjectedTextView(view: View): TextView? {
            var parent: ViewParent? = view.parent
            while (parent != null) {
                if (parent is ViewGroup) {
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        if (child is TextView && child.getTag(TAG_KEY) == true) return child
                    }
                }
                parent = if (parent is View) parent.parent else null
            }
            return null
        }

        private fun isInsideNetworkSpeedView(view: View, networkSpeedViewClass: Class<*>): Boolean {
            var parent: ViewParent? = view.parent
            while (parent != null) {
                if (networkSpeedViewClass.isInstance(parent)) return true
                parent = if (parent is View) parent.parent else null
            }
            return false
        }

        private fun startTempUpdate(handler: Handler, tempTextView: TextView, context: Context) {
            handler.post(object : Runnable {
                override fun run() {
                    val intent = context.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                    if (intent != null) {
                        val tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                        val celsius = Math.round(tempTenth / 10.0f)
                        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val current = batteryManager.javaClass
                            .getMethod("getIntProperty", Int::class.javaPrimitiveType)
                            .invoke(batteryManager, 2) as Int

                        val power = voltage.toFloat() * current.toFloat() / 1_000_000_000.0f
                        val powerString = if (power < 0) {
                            String.format(" %.2fw", power)
                        } else {
                            String.format(" %.2fw", power)
                        }
                        tempTextView.text = String.format(" %s℃ %s", celsius, powerString)
                    }
                    handler.postDelayed(this, REFRESH_MS)
                }
            })
        }

        private fun updateTextColorAndSize(
            parent: ViewGroup,
            targetTextView: TextView,
            context: Context
        ) {
            val clockView = try {
                parent.findViewById<TextView>(0x7f0a024f)
            } catch (_: Throwable) {
                null
            }

            if (clockView != null) {
                targetTextView.setTextColor(clockView.currentTextColor)
                val fontsize = clockView.textSize / context.resources.displayMetrics.scaledDensity
                targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontsize)
            } else {
                targetTextView.setTextColor(0xFFFFFFFF.toInt())
                targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        }
    }
}
