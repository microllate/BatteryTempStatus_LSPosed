package com.example.batterytemp

import android.view.ViewGroup
import android.widget.TextView
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {

    override fun onInit() = configs {
        isDebug = true
    }

    override fun onHook() = encase {
        loadApp(name = "com.android.systemui") {
            try {
                val classLoader = appClassLoader ?: run {
                    loggerD(msg = "BatteryTemp: SystemUI classLoader is null")
                    return@loadApp
                }

                val targetClass = Class.forName(
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
                    false,
                    classLoader
                )

                loggerD(msg = "BatteryTemp: target class found: ${targetClass.name}")

                findClass(targetClass.name).hook {
                    injectMember {
                        method {
                            name = "onFinishInflate"
                        }

                        afterHook {
                            try {
                                val statusBarView = instance as? ViewGroup
                                    ?: return@afterHook

                                if (statusBarView.findViewWithTag<TextView>(VIEW_TAG) != null) {
                                    return@afterHook
                                }

                                val density = statusBarView.resources.displayMetrics.density

                                val tempView = TextView(statusBarView.context).apply {
                                    tag = VIEW_TAG
                                    text = " 25℃"
                                    textSize = 11f
                                    setTextColor(0xFFFFFFFF.toInt())
                                    setPadding(0, 0, (4 * density).toInt(), 0)
                                }

                                statusBarView.addView(
                                    tempView,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                )

                                loggerD(msg = "BatteryTemp: test TextView added")
                            } catch (e: Throwable) {
                                loggerD(
                                    msg = "BatteryTemp: UI injection failed: ${e.stackTraceToString()}"
                                )
                            }
                        }
                    }
                }

                loggerD(msg = "BatteryTemp: SystemUI hook initialized successfully")
            } catch (e: Throwable) {
                loggerD(
                    msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}"
                )
            }
        }
    }

    companion object {
        private const val VIEW_TAG = "battery_temp_overlay"
    }
}
