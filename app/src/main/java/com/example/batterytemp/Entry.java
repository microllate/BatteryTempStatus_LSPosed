package com.example.batterytemp;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.widget.TextView;

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

        // 只 hook 两个类
        String[] targetClasses = new String[]{
                "com.android.systemui.battery.BatteryMeterView",
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        };

        for (String clsName : targetClasses) {
            try {
                final Class<?> cls = XposedHelpers.findClass(clsName, lpparam.classLoader);

                // hook updatePercentText()
                XposedHelpers.findAndHookMethod(cls, "updatePercentText", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object obj = param.thisObject;
                            TextView percentView = (TextView) XposedHelpers.getObjectField(obj, "mBatteryPercentView");
                            if (percentView == null) return;

                            Context context = (Context) XposedHelpers.getObjectField(obj, "mContext");
                            if (context == null) return;

                            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                            if (intent == null) return;

                            int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                            int celsius = Math.round(tempTenth / 10.0f);

                            CharSequence current = percentView.getText();
                            String text = current == null ? "" : current.toString();

                            if (!text.contains("℃")) {
                                percentView.setText(text.trim() + " " + celsius + "℃");
                            }

                        } catch (Throwable t) {
                            XposedBridge.log("BatteryTemp append failed: " + t);
                        }
                    }
                });

                XposedBridge.log("BatteryTemp: hooked " + clsName + "#updatePercentText");

            } catch (Throwable ignore) {
                XposedBridge.log("BatteryTemp: class not found " + clsName);
            }
        }
    }
}
