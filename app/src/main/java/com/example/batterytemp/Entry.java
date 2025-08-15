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
    private Handler handler;
    private Context systemUiContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        try {
            final Class<?> clazz = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(clazz, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (handler == null) {
                        handler = new Handler(Looper.getMainLooper());
                    }

                    ViewGroup statusBarView = (ViewGroup) param.thisObject;
                    systemUiContext = statusBarView.getContext();

                    // 使用你提供的 ID 0x7f0a082f 直接定位左侧容器
                    ViewGroup leftSideGroup = (ViewGroup) XposedHelpers.callMethod(statusBarView, "findViewById", 0x7f0a082f);

                    if (leftSideGroup != null) {
                        tempTextView = new TextView(systemUiContext);
                        
                        // 使用你找到的 ID 0x7f0a023d 直接定位时钟控件
                        TextView clockView = (TextView) XposedHelpers.callMethod(statusBarView, "findViewById", 0x7f0a023d);
                        
                        if (clockView != null) {
                            ColorStateList textColor = clockView.getTextColors();
                            if (textColor != null) {
                                tempTextView.setTextColor(textColor);
                            }
                            tempTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockView.getTextSize() / systemUiContext.getResources().getDisplayMetrics().scaledDensity);
                            
                            // 继承时钟的字体家族，使显示效果更统一
                            // Typeface typeface = clockView.getTypeface();
                            // if (typeface != null) {
                            //     tempTextView.setTypeface(typeface);
                            // }
                        } else {
                            // 如果找不到时钟，则使用默认值
                            tempTextView.setTextColor(0xFFFFFFFF); // 白色
                            tempTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        }

                        tempTextView.setPadding(0, 0, 10, 0);

                        leftSideGroup.addView(tempTextView, 3);
                        XposedBridge.log("BatteryTemp DEBUG: TextView added to left side of status bar.");
                        
                        startTempUpdate();
                    } else {
                        XposedBridge.log("BatteryTemp DEBUG: Left side container with ID 0x7f0a082f not found.");
                    }
                }
            });
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
}
