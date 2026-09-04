package com.example.batterytemp

import android.view.View
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
                loggerD(msg = "BatteryTemp: DexKit scanning SystemUI: $apkPath")
                System.loadLibrary("dexkit")

                DexKitBridge.create(apkPath).use { bridge ->
                    loggerD(msg = "BatteryTemp: DexKit initialized, dexCount=${bridge.getDexNum()}")
                    val targetData = bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher {
                            className("MiuiPhoneStatusBarView", StringMatchType.Contains)
                            methods {
                                add {
                                    name("onFinishInflate")
                                    returnType("void")
                                    paramCount(0)
                                }
                            }
                        }
                        findFirst = true
                    }.firstOrNull()

                    if (targetData == null) {
                        loggerD(msg = "BatteryTemp: DexKit target class not found")
                        return@use
                    }

                    val targetClass = targetData.getInstance(classLoader)
                    loggerD(msg = "BatteryTemp: DexKit target found: ${targetClass.name}")

                    findClass(targetClass.name).hook {
                        injectMember {
                            method { name = "onFinishInflate" }
                            afterHook {
                                try {
                                    val statusBarView = instance as? ViewGroup ?: return@afterHook
                                    loggerD(msg = "BatteryTemp: ===== STATUS BAR STRUCTURE BEGIN =====")
                                    dumpViewGroupFields(statusBarView)
                                    loggerD(msg = "BatteryTemp: root childCount=${statusBarView.childCount}")
                                    dumpViewTree(statusBarView, 0, 5)
                                    loggerD(msg = "BatteryTemp: ===== STATUS BAR STRUCTURE END =====")
                                } catch (e: Throwable) {
                                    loggerD(msg = "BatteryTemp: structure dump failed: ${e.stackTraceToString()}")
                                }
                            }
                        }
                    }
                    loggerD(msg = "BatteryTemp: SystemUI hook initialized successfully")
                }
            } catch (e: Throwable) {
                loggerD(msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}")
            }
        }
    }

    companion object {
        private fun dumpViewGroupFields(root: ViewGroup) {
            var type: Class<*>? = root.javaClass
            while (type != null && type != Any::class.java) {
                loggerD(msg = "BatteryTemp: CLASS ${type.name}")
                for (field in type.declaredFields) {
                    if (!ViewGroup::class.java.isAssignableFrom(field.type)) continue
                    try {
                        field.isAccessible = true
                        val value = field.get(root) as? ViewGroup ?: continue
                        if (value === root) continue
                        loggerD(
                            msg = "BatteryTemp: FIELD ${field.name} type=${field.type.name} " +
                                "value=${value.javaClass.name} childCount=${value.childCount} " +
                                "id=${resourceName(value)}"
                        )
                        dumpDirectChildren(value)
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: FIELD ${field.name} <error ${e.javaClass.simpleName}>")
                    }
                }
                type = type.superclass
            }
        }

        private fun dumpDirectChildren(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                val text = (child as? TextView)?.text?.toString()?.replace("\n", "\\n")
                loggerD(
                    msg = "BatteryTemp:   CHILD[$index] class=${child.javaClass.name} " +
                        "id=${resourceName(child)} text=${text ?: "<none>"} shown=${child.isShown}"
                )
            }
        }

        private fun dumpViewTree(view: View, depth: Int, maxDepth: Int) {
            if (depth > maxDepth) return
            val indent = "  ".repeat(depth)
            val text = (view as? TextView)?.text?.toString()?.replace("\n", "\\n")
            loggerD(
                msg = "BatteryTemp: TREE $indent${view.javaClass.name} " +
                    "id=${resourceName(view)} text=${text ?: "<none>"}"
            )
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    dumpViewTree(view.getChildAt(index), depth + 1, maxDepth)
                }
            }
        }

        private fun resourceName(view: View): String = try {
            if (view.id == View.NO_ID) "NO_ID" else view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            "id=${view.id}"
        }
    }
}
