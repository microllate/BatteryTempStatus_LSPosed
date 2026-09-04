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

                val apkPath = appInfo.sourceDir
                loggerD(msg = "BatteryTemp: DexKit scanning SystemUI: $apkPath")

                System.loadLibrary("dexkit")

                DexKitBridge.create(apkPath).use { bridge ->
                    loggerD(msg = "BatteryTemp: DexKit initialized, dexCount=${bridge.getDexNum()}")

                    val targetData = bridge.findClass {
                        searchPackages("com.android.systemui")
                        matcher {
                            className(
                                "MiuiPhoneStatusBarView",
                                StringMatchType.Contains
                            )
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

                                    val leftSideGroup = findLeftSideGroup(statusBarView)
                                    if (leftSideGroup == null) {
                                        loggerD(
                                            msg = "BatteryTemp: left side container not found"
                                        )
                                        return@afterHook
                                    }

                                    val tempView = TextView(statusBarView.context).apply {
                                        tag = VIEW_TAG
                                        text = " 25℃"
                                        applyClockStyle(statusBarView, this)
                                        setPadding(0, 0, dp(statusBarView, 10), 0)
                                    }

                                    val insertIndex = minOf(3, leftSideGroup.childCount)
                                    leftSideGroup.addView(tempView, insertIndex)

                                    loggerD(
                                        msg = "BatteryTemp: test TextView added to left side field"
                                    )
                                } catch (e: Throwable) {
                                    loggerD(
                                        msg = "BatteryTemp: UI injection failed: ${e.stackTraceToString()}"
                                    )
                                }
                            }
                        }
                    }

                    loggerD(msg = "BatteryTemp: SystemUI hook initialized successfully")
                }
            } catch (e: Throwable) {
                loggerD(
                    msg = "BatteryTemp: SystemUI hook failed: ${e.stackTraceToString()}"
                )
            }
        }
    }

    companion object {
        private const val VIEW_TAG = "battery_temp_overlay"

        /**
         * Resolve the actual container held by MiuiPhoneStatusBarView instead
         * of guessing from the rendered View tree. The field is discovered at
         * runtime, so no compiled SystemUI resource id is required.
         */
        private fun findLeftSideGroup(statusBarView: ViewGroup): ViewGroup? {
            findViewGroupField(statusBarView)?.let { return it }

            // Some HyperOS builds do not keep the container as a direct field.
            // Resource names are used only as a compatibility fallback and are
            // resolved dynamically from the active SystemUI Resources instance.
            return findGroupByResourceName(
                statusBarView,
                setOf(
                    "left_side",
                    "leftSide",
                    "status_bar_left_side",
                    "status_bar_left_container",
                    "status_bar_left"
                )
            )
        }

        private fun findViewGroupField(root: ViewGroup): ViewGroup? {
            var best: ViewGroup? = null
            var bestScore = Int.MIN_VALUE

            var type: Class<*>? = root.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (!ViewGroup::class.java.isAssignableFrom(field.type)) continue

                    try {
                        field.isAccessible = true
                        val value = field.get(root) as? ViewGroup ?: continue
                        if (value === root) continue

                        val name = field.name.lowercase()
                        var score = 0
                        if (name.contains("left")) score += 100
                        if (name.contains("status")) score += 30
                        if (name.contains("side")) score += 20
                        if (name.contains("group") || name.contains("container")) score += 10

                        val clock = findClockTextView(value)
                        if (clock != null) score += 80
                        if (value.childCount >= 2) score += 5

                        if (score > bestScore) {
                            bestScore = score
                            best = value
                        }
                    } catch (_: Throwable) {
                        // Hidden/inaccessible fields are expected on some ROMs.
                    }
                }
                type = type.superclass
            }

            if (best != null) {
                loggerD(msg = "BatteryTemp: resolved ViewGroup field: ${best.javaClass.name}")
            }
            return best
        }

        private fun findGroupByResourceName(
            root: ViewGroup,
            names: Set<String>
        ): ViewGroup? {
            val resources = root.resources

            fun visit(view: View): ViewGroup? {
                if (view is ViewGroup) {
                    val entryName = try {
                        resources.getResourceEntryName(view.id)
                    } catch (_: Throwable) {
                        null
                    }

                    if (entryName != null && names.any { entryName.equals(it, ignoreCase = true) }) {
                        return view
                    }

                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }

            return visit(root)
        }

        private fun applyClockStyle(parent: ViewGroup, target: TextView) {
            val clock = findClockTextView(parent)
            if (clock != null) {
                target.setTextColor(clock.currentTextColor)
                target.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    clock.textSize
                )
            } else {
                target.setTextColor(0xFFFFFFFF.toInt())
                target.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        }

        private fun findClockTextView(root: ViewGroup): TextView? {
            var best: TextView? = null
            var bestScore = Int.MIN_VALUE

            fun visit(view: View, depth: Int) {
                if (depth > 6) return
                if (view is TextView) {
                    val text = view.text?.toString().orEmpty()
                    var score = 0
                    if (text.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))) score += 100
                    if (view.isShown) score += 5
                    if (view.textSize > 0f) score += 1
                    if (score > bestScore) {
                        bestScore = score
                        best = view
                    }
                } else if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index), depth + 1)
                    }
                }
            }

            visit(root, 0)
            return best
        }

        private fun dp(view: View, value: Int): Int =
            (value * view.resources.displayMetrics.density).toInt()
    }
}
