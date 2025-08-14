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

public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        try {
            final Class<?> clazz = XposedHelpers.findClass(
                    "com.android.systemui.battery.BatteryMeterView",
                    lpparam.classLoader
            );

            XposedBridge.hookAllMethods(clazz, "updateShowPercent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object batteryMeterView = param.thisObject;
                    TextView percentView = findPercentTextView(batteryMeterView);

                    if (percentView != null) {
                        Context context = (Context) XposedHelpers.getObjectField(batteryMeterView, "mContext");
                        if (context == null) return;

                        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        if (intent == null) return;

                        int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        int celsius = Math.round(tempTenth / 10.0f);

                        CharSequence current = percentView.getText();
                        String updated = appendTemp(current == null ? "" : current.toString(), celsius);

                        if (!updated.contentEquals(current)) {
                            percentView.setText(updated);
                        }

                        // DEBUG日志
                        XposedBridge.log("BatteryTemp DEBUG: " + updated +
                                ", visibility=" + percentView.getVisibility() +
                                ", width=" + percentView.getWidth() +
                                ", height=" + percentView.getHeight());
                    } else {
                        XposedBridge.log("BatteryTemp DEBUG: TextView not found!");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("BatteryTemp DEBUG: hook failed: " + t);
        }
    }

    private TextView findPercentTextView(Object obj) {
        Field[] fields = obj.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (TextView.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof TextView) return (TextView) val;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private String appendTemp(String text, int celsius) {
        if (text == null) text = "";
        if (text.contains("℃")) return text; // 已经有温度
        return text.trim() + " " + celsius + "℃";
    }
}
