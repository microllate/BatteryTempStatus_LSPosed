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
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private TextView tempTextView = null;
    private ViewGroup statusBarView = null;
    private Handler handler;
    private Context systemUiContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        try {
            final Class<?> miuiStatusBarViewClazz = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
                    lpparam.classLoader
            );
            
            // Hook onFinishInflate 方法，用于添加 TextView
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

            // Hook NetworkSpeedView 的子 TextView 的 setTextColor(int) 方法
            final Class<?> networkSpeedViewClazz = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.views.NetworkSpeedView",
                    lpparam.classLoader
            );
            
            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof TextView && statusBarView != null && tempTextView != null) {
                            TextView currentTextView = (TextView) param.thisObject;
                            if (currentTextView.getParent() != null && 
                                currentTextView.getParent().getClass() == networkSpeedViewClazz) {
                                int color = (int) param.args[0];
                                tempTextView.setTextColor(color);
                            }
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
                        // 获取温度
                        int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        int celsius = Math.round(tempTenth / 10.0f);
                        
                        // 获取电压和电流
                        BatteryManager batteryManager = (BatteryManager) systemUiContext.getSystemService(Context.BATTERY_SERVICE);
                        int voltage = (int) XposedHelpers.callMethod(batteryManager, "getIntProperty", 2);
                        int current = (int) XposedHelpers.callMethod(batteryManager, "getIntProperty", 4);

                        long rawMicroV = readLongWithSu("/sys/class/power_supply/battery/voltage_now");
                        long rawMicroA = readLongWithSu("/sys/class/power_supply/battery/current_now");

                        XposedBridge.log("电流: " + current );
                        XposedBridge.log("电压: " + voltage );
                        XposedBridge.log("sys电流: " + rawMicroA );
                        XposedBridge.log("sys电压: " + rawMicroV );

                        for (int id = 0; id <= 20; id++) {
                         try {
                             int value = (int) XposedHelpers.callMethod(batteryManager, "getIntProperty", id);
                             XposedBridge.log("getIntProperty id=" + id + " value=" + value);
                         } catch (Throwable t) {
                             XposedBridge.log("id=" + id + " 调用异常: " + t);
                         }
                       }
                        
                        // 计算功率，单位为毫瓦 (mW)。假设 voltage 为 mV，current 为 mA。
                        float power = (float)voltage * (float)current / 10000000.0f;
                        
                        String powerString;
                        if (power < 0) {
                            powerString = String.format("充电 %.2fW", power);
                        } else {
                            powerString = String.format("耗电 %.2fW", power);
                        }
                        
                        // 组合文本并更新UI
                        String tempString = String.format(" %s℃ %s", celsius, powerString);
                        tempTextView.setText(tempString);
                    }
                }
                handler.postDelayed(this, 2000); // 1秒更新一次
            }
        });
    }

    private void updateTextColorAndSize(ViewGroup parent, TextView targetTextView) {
        TextView clockView = (TextView) XposedHelpers.callMethod(parent, "findViewById", 0x7f0a023d);
        if (clockView != null) {
            targetTextView.setTextColor(clockView.getCurrentTextColor());
            float fontSize = clockView.getTextSize() / systemUiContext.getResources().getDisplayMetrics().scaledDensity;
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        } else {
            targetTextView.setTextColor(0xFFFFFFFF);
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
    }

    private static long readLongWithSu(String path) {
        java.io.BufferedReader br = null;
        java.io.BufferedReader errBr = null;
        try {
            // 使用 sh 作为桥梁来执行 su
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "su -c 'cat " + path + "'"});

            br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            errBr = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));

            String line = br.readLine();
            int exitCode = p.waitFor();

            String errorLine;
            while ((errorLine = errBr.readLine()) != null) {
                XposedBridge.log("su 错误流: " + errorLine);
            }

            if (exitCode == 0 && line != null) {
                return Long.parseLong(line.trim());
            } else {
                XposedBridge.log("su 读取失败: " + path + " exit=" + exitCode + " line=" + line);
            }
        } catch (Throwable t) {
            XposedBridge.log("su 读取异常: " + path + " -> " + t);
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
            try { if (errBr != null) errBr.close(); } catch (Throwable ignored) {}
        }
        return -1;
    }
}
