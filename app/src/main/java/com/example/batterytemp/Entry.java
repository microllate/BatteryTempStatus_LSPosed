package com.example.batterytemp;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.BatteryManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";

    // 简单缓存，降低 registerReceiver 调用频率
    private static final AtomicLong sLastTs = new AtomicLong(0);
    private static final AtomicInteger sLastTempC = new AtomicInteger(0);

    private static class Drawer {
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        Drawer(float sp, float density) {
            float px = sp * density;         // sp->px（用 density 近似即可）
            text.setColor(Color.WHITE);
            text.setTextSize(px);
            text.setFakeBoldText(true);
            text.setShadowLayer(px * 0.15f, 0, 0, 0x66000000);

            stroke.setColor(0xAA000000);
            stroke.setTextSize(px);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, px * 0.10f));
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpp.packageName)) return;

        // 1) AOSP/Pixel 通用类
        hookDraw(lpp, "com.android.settingslib.graph.BatteryMeterDrawableBase");
        // 2) 某些 ROM 的变体（命名示例，存在就 Hook）
        hookDraw(lpp, "com.android.systemui.battery.BatteryMeterDrawable");
        hookDraw(lpp, "com.android.systemui.battery.BatteryMeterView$BatteryMeterDrawable");
        hookDraw(lpp, "com.miui.systemui.BatteryMeterDrawable");          // MIUI/HyperOS 可能存在
        hookDraw(lpp, "com.android.systemui.miui.BatteryMeterDrawable");  // 另一种命名

        XposedBridge.log("BatteryTempOverlay: hook requested.");
    }

    private void hookDraw(final XC_LoadPackage.LoadPackageParam lpp, final String clazz) {
        try {
            Class<?> c = XposedHelpers.findClass(clazz, lpp.classLoader);
            XposedBridge.hookAllMethods(c, "draw", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object thiz = param.thisObject;
                    Canvas canvas = (Canvas) param.args[0];
                    if (canvas == null || thiz == null) return;

                    // 取 Context
                    Context ctx = (Context) XposedHelpers.getObjectField(thiz, "mContext");
                    if (ctx == null) return;

                    // 取温度（带 1s 缓存）
                    int celsius = readBatteryTempCached(ctx);

                    // 取图标 bounds
                    Rect b = (Rect) XposedHelpers.callMethod(thiz, "getBounds");
                    if (b == null) return;

                    float density = ctx.getResources().getDisplayMetrics().density;

                    // 字体大小：跟随电池高度
                    float textSp = Math.max(10f, b.height() * 0.55f / density);
                    Drawer drawer = new Drawer(textSp, density);

                    String text = celsius + "℃";

                    // 位置：电池右侧，垂直居中
                    float x = b.right + 4f * density;
                    // baseline 需要加上文字高度的一半（近似）
                    Paint.FontMetrics fm = drawer.text.getFontMetrics();
                    float textH = fm.bottom - fm.top;
                    float y = b.centerY() + textH * 0.35f; // 微调到视觉居中

                    // 先描边再填充，保证任何背景下可读
                    canvas.drawText(text, x, y, drawer.stroke);
                    canvas.drawText(text, x, y, drawer.text);
                }
            });
            XposedBridge.log("BatteryTempOverlay: hooked draw() on " + clazz);
        } catch (Throwable t) {
            // 类可能不存在，忽略
        }
    }

    private static int readBatteryTempCached(Context ctx) {
        long now = System.currentTimeMillis();
        long last = sLastTs.get();
        if (now - last < 1000) {
            return sLastTempC.get();
        }
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int tempTenth = (i != null) ? i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        int celsius = Math.round(tempTenth / 10.0f);
        sLastTempC.set(celsius);
        sLastTs.set(now);
        return celsius;
    }
}
