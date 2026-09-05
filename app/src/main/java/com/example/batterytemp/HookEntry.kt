package com.example.batterytemp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
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
import java.lang.ref.WeakReference
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

                                    val leftSideGroup = findLeftSideGroup(statusBarView)
                                    if (leftSideGroup == null) {
                                        loggerD(msg = "BatteryTemp: left status bar container not found")
                                        return@afterHook
                                    }

                                    val tempView = TextView(statusBarView.context).apply {
                                        setTag(TAG_KEY, true)
                                        setPadding(0, 0, 10, 0)
                                    }

                                    updateTextColorAndSize(statusBarView, tempView)
                                    leftSideGroup.addView(tempView, 1)
                                    statusBarView.setTag(TAG_KEY, true)

                                    startTemperatureUpdater(tempView)
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

                    // setTextColor is inherited from View; YukiHookAPI's member resolver
                    // may resolve it against View and fail. Hook the concrete override only
                    // when NetworkSpeedView actually declares one. If it does not, the
                    // initial color remains synchronized by updateTextColorAndSize().
                    try {
                        val declaredSetTextColor = networkSpeedViewClass.declaredMethods.firstOrNull { method ->
                            method.name == "setTextColor" &&
                                method.parameterTypes.size == 1 &&
                                method.parameterTypes[0] == Int::class.javaPrimitiveType
                        }

                        if (declaredSetTextColor != null) {
                            findClass(networkSpeedViewClass.name).hook {
                                injectMember {
                                    method {
                                        name = declaredSetTextColor.name
                                        param(Int::class.javaPrimitiveType!!)
                                    }
                                    afterHook {
                                        try {
                                            injectedTextView?.get()?.setTextColor(args(0).int())
                                        } catch (e: Throwable) {
                                            loggerD(msg = "BatteryTemp: network color sync failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                            loggerD(msg = "BatteryTemp: NetworkSpeedView declares setTextColor; color sync hook installed")
                        } else {
                            loggerD(msg = "BatteryTemp: NetworkSpeedView does not declare setTextColor; using direct color sync fallback")
                        }
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: NetworkSpeedView color hook setup failed: ${e.message}")
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
        private var injectedTextView: WeakReference<TextView>? = null

        private fun startTemperatureUpdater(target: TextView) {
            val handler = Handler(Looper.getMainLooper())
            val targetReference = WeakReference(target)
            val context = target.context.applicationContext
            val batteryIntent = arrayOfNulls<Intent>(1)
            var registered = false

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        batteryIntent[0] = intent
                    }
                }
            }

            lateinit var update: Runnable

            fun stop() {
                handler.removeCallbacks(update)
                if (registered) {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: Throwable) {
                    }
                    registered = false
                }
                batteryIntent[0] = null
            }

            fun start() {
                if (registered) return

                try {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val stickyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            receiver,
                            filter,
                            Context.RECEIVER_EXPORTED
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(receiver, filter)
                    }
                    batteryIntent[0] = stickyIntent
                    registered = true
                } catch (e: Throwable) {
                    loggerD(msg = "BatteryTemp: battery receiver registration failed: ${e.message}")
                }

                handler.removeCallbacks(update)
                handler.post(update)
            }

            update = object : Runnable {
                override fun run() {
                    val view = targetReference.get()
                    if (view == null || !view.isAttachedToWindow) {
                        stop()
                        return
                    }

                    try {
                        val intent = batteryIntent[0]
                        if (intent != null) {
                            val tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                            val celsius = Math.round(tempTenth / 10.0f)
                            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                            val batteryManager =
                                context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                            if (batteryManager != null) {
                                val current = batteryManager.getIntProperty(2)
                                val power = voltage.toFloat() * current.toFloat() / 1_000_000_000.0f
                                val powerString = String.format(Locale.getDefault(), " %.2fw", power)
                                view.text = String.format(Locale.getDefault(), " %s℃ %s", celsius, powerString)
                            }
                        }
                    } catch (e: Throwable) {
                        loggerD(msg = "BatteryTemp: temperature/power update failed: ${e.message}")
                    }
                    handler.postDelayed(this, REFRESH_MS)
                }
            }

            target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { start() }
                override fun onViewDetachedFromWindow(v: View) { stop() }
            })

            injectedTextView = targetReference

            if (target.isAttachedToWindow) {
                start()
            }
        }

        private fun updateTextColorAndSize(root: ViewGroup, target: TextView) {
            val clock = findClockView(root)
            if (clock != null) {
                target.setTextColor(clock.currentTextColor)
                target.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize)
            }
        }

        private fun findLeftSideGroup(root: ViewGroup): ViewGroup? {
            val context = root.context
            val resourceNames = arrayOf(
                "phone_status_bar_left_container",
                "status_bar_left_container",
                "status_bar_left"
            )
            for (name in resourceNames) {
                val id = context.resources.getIdentifier(name, "id", "com.android.systemui")
                if (id != 0) {
                    val view = root.findViewById<View>(id)
                    if (view is ViewGroup) return view
                }
            }
            return findViewGroup(root) { view ->
                val role = view.semanticName(context)
                role.contains("left") &&
                    (role.contains("container") || role.contains("statusbar") || role.contains("status_bar"))
            }
        }

        private fun findClockView(root: ViewGroup): TextView? {
            val context = root.context
            val clockIds = arrayOf("clock", "status_bar_clock")
            for (name in clockIds) {
                val id = context.resources.getIdentifier(name, "id", "com.android.systemui")
                if (id != 0) {
                    val view = root.findViewById<View>(id)
                    if (view is TextView) return view
                }
            }

            var classNameCandidate: TextView? = null
            return findTextView(root) { view ->
                val resourceName = view.resourceEntryName(context)
                if (resourceName == "clock" || resourceName == "status_bar_clock") return@findTextView true
                if (resourceName.endsWith("_clock") || resourceName.endsWith("clock")) return@findTextView true

                if (classNameCandidate == null) {
                    val simpleName = view.javaClass.simpleName
                    if (simpleName == "Clock" || simpleName.endsWith("Clock")) {
                        classNameCandidate = view
                    }
                }
                false
            } ?: classNameCandidate
        }

        private fun findViewGroup(root: ViewGroup, predicate: (View) -> Boolean): ViewGroup? {
            if (predicate(root)) return root
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ViewGroup) {
                    val result = findViewGroup(child, predicate)
                    if (result != null) return result
                }
            }
            return null
        }

        private fun findTextView(root: ViewGroup, predicate: (TextView) -> Boolean): TextView? {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is TextView && predicate(child)) return child
                if (child is ViewGroup) {
                    val result = findTextView(child, predicate)
                    if (result != null) return result
                }
            }
            return null
        }

        private fun View.resourceEntryName(context: Context): String {
            return try {
                if (id == View.NO_ID) return ""
                context.resources.getResourceEntryName(id)
            } catch (_: Throwable) {
                ""
            }
        }

        private fun View.semanticName(context: Context): String {
            val resourceName = resourceEntryName(context)
            return if (resourceName.isNotEmpty()) resourceName else javaClass.simpleName
        }

        private fun readField(instance: Any, name: String): Any? {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try {
                    val field: Field = type.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(instance)
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                } catch (_: Throwable) {
                    return null
                }
            }
            return null
        }
    }
}
