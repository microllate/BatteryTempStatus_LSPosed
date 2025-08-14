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

        hookBatteryViews(lpparam);
    }

    private void hookBatteryViews(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 只针对你的系统存在的类
        String[] targetClasses = new String[]{
                "com.android.systemui.battery.BatteryMeterView",
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        };

        for (String cls : targetClasses) {
            try {
                final Class<?> target = XposedHelpers.findClass(cls, lpparam.classLoader);

                // 只 hook updateShowPercent() 方法
                XposedBridge.hookAllMethods(target, "updateShowPercent", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        appendBatteryTemp(param.thisObject);
                    }
                });

                XposedBridge.log("BatteryTempStatus: hooked " + cls + "#updateShowPercent");

            } catch (Throwable t) {
                XposedBridge.log("BatteryTempStatus: class not found " + cls + " - " + t);
            }
        }
    }

    private void appendBatteryTemp(Object batteryViewObj) {
        try {
            Context context = (Context) XposedHelpers.getObjectField(batteryViewObj, "mContext");
            if (context == null) return;

            // 获取电池温度（0.1°C单位）
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return;
            int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int celsius = Math.round(tempTenth / 10.0f);

            // 获取 mBatteryPercentView 字段（TextView）
            Field field = batteryViewObj.getClass().getDeclaredField("mBatteryPercentView");
            field.setAccessible(true);
            Object view = field.get(batteryViewObj);
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                CharSequence text = tv.getText();
                String newText = appendTemp(text == null ? "" : text.toString(), celsius);
                if (!newText.contentEquals(text)) {
                    tv.setText(newText);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("BatteryTempStatus append failed: " + t);
        }
    }

    private String appendTemp(String text, int celsius) {
        if (text.contains("℃")) return text; // 已有温度
        return text.trim() + " " + celsius + "℃";
    }
}
