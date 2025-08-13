package com.example.batterytemp;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.widget.TextView;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed/Xposed entry. Hooks SystemUI's BatteryMeterView (and MIUI variant)
 * to append battery temperature to the percentage text, e.g., "85% 38℃".
 */
public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;
        hookBatteryViews(lpparam);
    }

    private void hookBatteryViews(final XC_LoadPackage.LoadPackageParam lpparam) {
        String[] targetClasses = new String[]{
                "com.android.systemui.BatteryMeterView",
                "com.android.systemui.battery.BatteryMeterView",
                "com.android.systemui.statusbar.policy.BatteryControllerImpl$BatteryStateChangeCallback", // fallback
                "com.android.systemui.MiuiBatteryMeterView", // MIUI/HyperOS (if exists)
                "com.miui.systemui.BatteryMeterView" // some ROM variants
        };

        // Try to hook likely update methods on BatteryMeterView variants.
        String[] methods = new String[]{
                "updatePercentText",
                "onBatteryLevelChanged",
                "setPercentShowMode",
                "updateShowPercent",
                "updateViews"
        };

        for (String cls : targetClasses) {
            try {
                final Class<?> target = XposedHelpers.findClass(cls, lpparam.classLoader);
                for (String m : methods) {
                    try {
                        XposedBridge.hookAllMethods(target, m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                tryAppendTemp(param.thisObject);
                            }
                        });
                        XposedBridge.log("BatteryTempStatus: hooked " + cls + "#" + m);
                    } catch (Throwable ignore) {
                        // method may not exist; try next
                    }
                }
            } catch (Throwable ignore) {
                // class not found; try next
            }
        }
    }

    private void tryAppendTemp(Object batteryMeterViewObj) {
        try {
            Context context = (Context) XposedHelpers.getObjectField(batteryMeterViewObj, "mContext");
            if (context == null) return;

            // Read temperature (0.1°C units)
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return;
            int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int celsius = Math.round(tempTenth / 10.0f);

            // Find the TextView field that holds percent text.
            TextView percentView = findPercentTextView(batteryMeterViewObj);
            if (percentView == null) {
                // Fallback: some ROMs keep percent text in a CharSequence field; try to update that.
                Object textObj = XposedHelpers.getObjectField(batteryMeterViewObj, "mPercentViewText");
                if (textObj instanceof CharSequence) {
                    String text = textObj.toString();
                    String newText = appendTemp(text, celsius);
                    XposedHelpers.setObjectField(batteryMeterViewObj, "mPercentViewText", newText);
                }
                return;
            }

            CharSequence current = percentView.getText();
            String updated = appendTemp(current == null ? "" : current.toString(), celsius);
            if (!updated.contentEquals(current)) {
                percentView.setText(updated);
            }
        } catch (Throwable t) {
            XposedBridge.log("BatteryTempStatus append failed: " + t);
        }
    }

    private String appendTemp(String text, int celsius) {
        if (text == null) text = "";
        if (text.contains("℃")) return text; // already appended
        // common forms: "85%", "85 %"
        return text.trim() + " " + celsius + "℃";
    }

    private TextView findPercentTextView(Object obj) {
        // Heuristics: scan fields for a TextView named like "*Percent*"
        Field[] fields = obj.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (TextView.class.isAssignableFrom(f.getType())) {
                String name = f.getName().toLowerCase();
                if (name.contains("percent") || name.contains("percentage") || name.contains("text")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val instanceof TextView) {
                            return (TextView) val;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        // Second pass: return any TextView (best effort)
        for (Field f : fields) {
            if (TextView.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof TextView) {
                        return (TextView) val;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
