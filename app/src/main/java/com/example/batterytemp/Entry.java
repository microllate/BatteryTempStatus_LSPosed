package com.example.batterytemp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只 hook SystemUI
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("BatteryTempOverlay: Loading in SystemUI");

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settingslib.graph.BatteryMeterDrawableBase",
                    lpparam.classLoader,
                    "draw",
                    Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Canvas canvas = (Canvas) param.args[0];
                            if (canvas == null) return;

                            Drawable drawable = (Drawable) param.thisObject;
                            Rect bounds = drawable.getBounds();

                            // 获取温度（这里用 sys/class/power_supply）
                            String tempText = getBatteryTemp() + "°C";

                            Paint paint = new Paint();
                            paint.setColor(Color.RED);
                            paint.setTextSize(bounds.height() * 0.6f); // 跟电池高度适配
                            paint.setAntiAlias(true);
                            paint.setFakeBoldText(true);

                            // 在电池右边画温度
                            float x = bounds.right + 4; // 右移一点
                            float y = bounds.bottom - (bounds.height() * 0.2f); // 底部上移一点
                            canvas.drawText(tempText, x, y, paint);

                            XposedBridge.log("BatteryTempOverlay: drew temp '" + tempText + "' at " + x + "," + y);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("BatteryTempOverlay ERROR: " + t);
        }
    }

    // 从系统文件读取温度
    private String getBatteryTemp() {
        try {
            File f = new File("/sys/class/power_supply/battery/temp");
            if (f.exists()) {
                String content = new java.util.Scanner(f).useDelimiter("\\A").next().trim();
                int temp = Integer.parseInt(content);
                return String.valueOf(temp / 10.0); // 部分设备温度是以 0.1°C 为单位
            }
        } catch (Throwable e) {
            XposedBridge.log("BatteryTempOverlay: read temp failed " + e);
        }
        return "--";
    }
}
