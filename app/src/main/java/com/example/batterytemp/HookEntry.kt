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
import android.widget.TextView
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
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
                                    if (statusBarView.getTag(TAG_KEY) != null) return@afterHook

                                    val leftSideGroup = findLeftSideGroup(statusBarView)
                                    if (leftSideGroup == null) {
                                        loggerD(msg = "BatteryTemp: left status bar container not found")
                                        return@afterHook
                                    }

                                    val context = statusBarView.context
                                    val tempView = TextView(context)
                                    tempView.setTag(TAG_KEY, true)
                                    tempView.setSingleLine(true)
                                    applyBatteryStyle(statusBarView, tempView)
                                    tempView.setPadding(0, 0, dp(context, 10), 0)

                                    val insertIndex = minOf(3, leftSideGroup.childCount)
                                    leftSideGroup.addView(tempView, insertIndex)
                                    statusBarView.setTag(TAG_KEY, true)

                                    loggerD(
                                        msg = "BatteryTemp: injected into " +
                                            "${leftSideGroup.javaClass.name} id=${resourceName(leftSideGroup)} " +
                                            "index=$insertIndex"
                                    )

                                    startTemperatureUpdater(statusBarView, tempView)
                                } catch (e: Throwable) {
                                    loggerD(msg = "BatteryTemp: injection failed: ${e.stackTraceToString()}")
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
        private const val TAG_KEY = 0x42545431
        private const val REFRESH_MS = 2000L
        private const val STYLE_REFRESH_MS = 200L

        private fun findLeftSideGroup(root: ViewGroup): ViewGroup? {
            val targetName = "phone_status_bar_left_container"
            val targetId = try {
                root.resources.getIdentifier(targetName, "id", root.context.packageName)
            } catch (_: Throwable) {
                0
            }

            if (targetId != 0) {
                root.findViewById<View>(targetId)?.let { view ->
                    if (view is ViewGroup) return view
                }
            }

            return findViewGroupByResourceName(root, targetName)
        }

        private fun findViewGroupByResourceName(root: ViewGroup, targetName: String): ViewGroup? {
            if (resourceName(root) == targetName) return root
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is ViewGroup) {
                    findViewGroupByResourceName(child, targetName)?.let { return it }
                }
            }
            return null
        }

        /**
         * Size follows the actual battery percentage TextView, while color follows network speed.
         * The percentage view is the correct visual reference for the right-side battery text.
         */
        private fun applyBatteryStyle(parent: ViewGroup, target: TextView) {
            val batteryPercent = findBatteryPercentTextView(parent)
            val networkSpeedText = findNetworkSpeedTextView(parent)

            if (networkSpeedText != null) {
                target.setTextColor(networkSpeedText.currentTextColor)
            } else {
                target.setTextColor(0xFFFFFFFF.toInt())
            }

            if (batteryPercent != null) {
                val fontSizePx = batteryPercent.textSize
                if (fontSizePx > 0f) {
                    target.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                }
                loggerD(
                    msg = "BatteryTemp: synced font from battery percent " +
                        "${batteryPercent.javaClass.name} id=${resourceName(batteryPercent)} " +
                        "sizePx=${batteryPercent.textSize}"
                )
            } else {
                target.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                loggerD(msg = "BatteryTemp: battery percent TextView not found, using fallback size")
            }
        }

        private fun findBatteryPercentTextView(root: ViewGroup): TextView? {
            // Prefer the actual battery percentage TextView by resource/class semantics.
            val idNames = arrayOf("battery_percent", "battery_percentage", "battery_level")
            for (name in idNames) {
                val found = findTextViewByResourceName(root, name)
                if (found != null) return found
            }

            // On HyperOS the percentage may live inside MiuiBatteryMeterView or a related container.
            if (root.javaClass.name.contains("BatteryMeterView", ignoreCase = true) ||
                root.javaClass.name.contains("BatteryContainer", ignoreCase = true)) {
                findPercentTextView(root)?.let { return it }
            }

            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is ViewGroup) {
                    findBatteryPercentTextView(child)?.let { return it }
                }
            }
            return null
        }

        private fun findPercentTextView(root: ViewGroup): TextView? {
            if (root is TextView && looksLikePercentage(root)) return root
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is TextView && looksLikePercentage(child)) return child
                if (child is ViewGroup) {
                    findPercentTextView(child)?.let { return it }
                }
            }
            return null
        }

        private fun looksLikePercentage(view: TextView): Boolean {
            val name = resourceName(view).lowercase(Locale.ROOT)
            if (name.contains("percent") || name.contains("percentage")) return true
            val text = view.text?.toString()?.trim() ?: return false
            return text.endsWith("%") && text.length <= 5
        }

        private fun findTextViewByResourceName(root: ViewGroup, targetName: String): TextView? {
            if (root is TextView && resourceName(root) == targetName) return root
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is TextView && resourceName(child) == targetName) return child
                if (child is ViewGroup) {
                    findTextViewByResourceName(child, targetName)?.let { return it }
                }
            }
            return null
        }

        private fun findNetworkSpeedTextView(root: ViewGroup): TextView? {
            val className = root.javaClass.name
            if (className.contains("NetworkSpeedView", ignoreCase = true)) {
                findFirstTextView(root)?.let { return it }
            }

            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is ViewGroup) {
                    findNetworkSpeedTextView(child)?.let { return it }
                }
            }
            return null
        }

        private fun findFirstTextView(root: ViewGroup): TextView? {
            if (root is TextView) return root
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is TextView) return child
                if (child is ViewGroup) {
                    findFirstTextView(child)?.let { return it }
                }
            }
            return null
        }

        private fun startTemperatureUpdater(parent: ViewGroup, target: TextView) {
            val handler = Handler(Looper.getMainLooper())
            val update = object : Runnable {
                override fun run() {
                    if (!target.isAttachedToWindow) return
                    try {
                        syncBatteryStyle(parent, target)

                        val intent = parent.context.registerReceiver(
                            null,
                            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                        )

                        val tempTenth = intent?.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE,
                            Int.MIN_VALUE
                        ) ?: Int.MIN_VALUE

                        if (tempTenth != Int.MIN_VALUE) {
                            val celsius = Math.round(tempTenth / 10.0f)
                            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                            val batteryManager = parent.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                            val current = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                                ?: Int.MIN_VALUE

                            if (voltage >= 0 && current != Int.MIN_VALUE) {
                                val power = voltage.toFloat() * current.toFloat() / 1_000_000_000.0f
                                val powerString = String.format(Locale.getDefault(), "%.2fW", power)
                                target.text = String.format(Locale.getDefault(), " %s℃ %s", celsius, powerString)
                            } else {
                                target.text = " ${celsius}℃"
                            }
                        }

                        handler.postDelayed(this, REFRESH_MS)
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: temperature/power update failed: ${e.stackTraceToString()}")
                        handler.postDelayed(this, REFRESH_MS)
                    }
                }
            }
            handler.post(update)
        }

        private fun syncBatteryStyle(parent: ViewGroup, target: TextView) {
            val batteryPercent = findBatteryPercentTextView(parent)
            if (batteryPercent != null) {
                val fontSizePx = batteryPercent.textSize
                if (fontSizePx > 0f && target.textSize != fontSizePx) {
                    target.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                }
            }

            val networkSpeedText = findNetworkSpeedTextView(parent)
            if (networkSpeedText != null) {
                val color = networkSpeedText.currentTextColor
                if (target.currentTextColor != color) {
                    target.setTextColor(color)
                }
            }
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        private fun resourceName(view: View): String = try {
            if (view.id == View.NO_ID) "NO_ID" else view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            "id=${view.id}"
        }
    }
}
