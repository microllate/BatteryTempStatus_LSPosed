package com.example.batterytemp;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed/Xposed entry. 在电池图标左侧显示温度。
 */
public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        hookBatteryDrawable(lpparam);
    }

    private void hookBatteryDrawable(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> drawableClass = XposedHelpers.findClass(
                    "com.android.settingslib.graph.BatteryMeterDrawableBase",
                    lpparam.classLoader
            );

            XposedBridge.hookAllMethods(drawableClass, "draw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object drawableObj = param.thisObject;
                    Canvas canvas = (Canvas) param.args[0];

                    // 获取电池位置
                    Rect bounds = (Rect) XposedHelpers.callMethod(drawableObj, "getBounds");
                    if (bounds == null) return;

                    // 获取温度
                    int tempC = getBatteryTemp((Context) XposedHelpers.getObjectField(drawableObj, "mContext"));

                    // 绘制温度
                    Paint paint = new Paint();
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(bounds.height() * 0.6f);
                    paint.setAntiAlias(true);

                    String tempText = tempC + "℃";
                    float textWidth = paint.measureText(tempText);

                    // 电池左边 8px 处绘制温度
                    float x = bounds.left - textWidth - 8;
                    float y = bounds.centerY() - ((paint.descent() + paint.ascent()) / 2);

                    canvas.drawText(tempText, x, y, paint);

                    XposedBridge.log("BatteryTempOverlay: drew temperature " + tempText);
                }
            });

            XposedBridge.log("BatteryTempOverlay: hooked draw() on BatteryMeterDrawableBase");
        } catch (Throwable t) {
            XposedBridge.log("BatteryTempOverlay: hook failed: " + t);
        }
    }

    private int getBatteryTemp(Context context) {
        try {
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return 0;
            int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            return Math.round(tempTenth / 10.0f);
        } catch (Throwable t) {
            Log.e("BatteryTempOverlay", "getBatteryTemp failed: " + t);
            return 0;
        }
    }
}
