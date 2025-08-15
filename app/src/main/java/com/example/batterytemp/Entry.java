package com.example.batterytemp;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private TextView tempTextView = null;
    private ViewGroup statusBarView = null; // 用于在 animateColorChange Hook 中访问状态栏
    private Handler handler;
    private Context systemUiContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        try {
            // Hook onFinishInflate 方法来添加我们的 TextView
            final Class<?> miuiStatusBarViewClazz = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
                    lpparam.classLoader
            );
            
            XposedHelpers.findAndHookMethod(miuiStatusBarViewClazz, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (handler == null) {
                        handler = new Handler(Looper.getMainLooper());
                    }
                    
                    statusBarView = (ViewGroup) param.thisObject;
                    systemUiContext = statusBarView.getContext();

                    ViewGroup leftSideGroup = (ViewGroup) XposedHelpers.callMethod(statusBarView, "findViewById", 0x7f0a082f);

                    if (leftSideGroup != null) {
                        tempTextView = new TextView(systemUiContext);
                        updateTextColorAndSize(statusBarView, tempTextView);
                        tempTextView.setPadding(0, 0, 10, 0);
                        leftSideGroup.addView(tempTextView, 3);
                        XposedBridge.log("BatteryTemp DEBUG: TextView added to left side of status bar.");
                        startTempUpdate();
                    } else {
                        XposedBridge.log("BatteryTemp DEBUG: Left side container with ID 0x7f0a082f not found.");
                    }
                }
            });

            // Hook animateColorChange 方法来同步颜色
            final Class<?> animatableClockViewClazz = XposedHelpers.findClass(
                    "com.android.systemui.shared.clocks.AnimatableClockView",
                    lpparam.classLoader
            );
            
            XposedHelpers.findAndHookMethod(animatableClockViewClazz, "animateColorChange",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (tempTextView != null && statusBarView != null) {
                            updateTextColorAndSize(statusBarView, tempTextView);
                            XposedBridge.log("BatteryTemp DEBUG: Text color updated due to animateColorChange.");
                        }
                    }
                }
            );

        } catch (Throwable t) {
            XposedBridge.log("BatteryTemp DEBUG: hook failed: " + t);
        }
    }

    private void startTempUpdate() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (tempTextView != null && systemUiContext != null) {
                    Intent intent = systemUiContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (intent != null) {
                        int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        int celsius = Math.round(tempTenth / 10.0f);
                        String tempString = " " + celsius + "℃";
                        tempTextView.setText(tempString);
                    }
                }
                handler.postDelayed(this, 5000);
            }
        });
    }

    private void updateTextColorAndSize(ViewGroup parent, TextView targetTextView) {
        TextView clockView = (TextView) XposedHelpers.callMethod(parent, "findViewById", 0x7f0a023d);
        if (clockView != null) {
            ColorStateList textColor = clockView.getTextColors();
            if (textColor != null) {
                targetTextView.setTextColor(textColor);
            }
            float fontSize = clockView.getTextSize() / systemUiContext.getResources().getDisplayMetrics().scaledDensity;
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        } else {
            targetTextView.setTextColor(0xFFFFFFFF);
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
    }
}
