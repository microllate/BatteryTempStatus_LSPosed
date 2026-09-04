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
                                        msg = "BatteryTemp: test TextView added to left side"
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
         * Resolve the left-side status-bar container without depending on a
         * compiled SystemUI resource id. We prefer semantic view names and
         * fall back to structural matching for HyperOS variants.
         */
        private fun findLeftSideGroup(statusBarView: ViewGroup): ViewGroup? {
            val named = findGroupByResourceName(
                statusBarView,
                setOf(
                    "left_side",
                    "leftSide",
                    "status_bar_left_side",
                    "status_bar_left_container",
                    "status_bar_left"
                )
            )
            if (named != null) return named

            return findLikelyLeftGroup(statusBarView)
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

        /**
         * Structural fallback for SystemUI versions whose resource names are
         * obfuscated/changed. The old implementation inserted into the third
         * child of a left-side group, so prefer shallow horizontal containers
         * with several TextView-like children and a clock-looking child.
         */
        private fun findLikelyLeftGroup(root: ViewGroup): ViewGroup? {
            var best: ViewGroup? = null
            var bestScore = Int.MIN_VALUE

            fun visit(view: View, depth: Int) {
                if (depth > 5) return
                if (view is ViewGroup) {
                    var textCount = 0
                    var visibleCount = 0
                    var score = 0

                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index)
                        if (child.visibility == View.VISIBLE) visibleCount++
                        if (child is TextView) {
                            textCount++
                            val text = child.text?.toString().orEmpty()
                            if (text.contains(":") || text.matches(Regex(".*\\d{1,2}.*"))) {
                                score += 3
                            }
                        }
                    }

                    if (view.childCount >= 2) {
                        score += textCount * 2
                        score += visibleCount
                        if (view.childCount >= 3) score += 2

                        if (score > bestScore) {
                            bestScore = score
                            best = view
                        }
                    }

                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index), depth + 1)
                    }
                }
            }

            visit(root, 0)
            return best
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
