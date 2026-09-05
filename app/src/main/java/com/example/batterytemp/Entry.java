package com.android.batterytempstatuslsp;

import android.app.AndroidAppHelper;
import android.content.Context;//通过 Context 你能拿到系统级服务、资源和应用环境
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class Entry implements IXposedHookLoadPackage{


    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private TextView tempTextView = null;
    private ViewGroup statusBarView = null;
    private Handler handler;
    private Context systemUiContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable{
        //handleLoadPackage 是 Xposed / LSPosed 框架中 IXposedHookLoadPackage 接口定义的核心回调方法
        // throws它的作用是告诉编译器和系统：“这个方法在运行过程中可能会抛出错误（异常），但我现在不在此处处理它，而是直接向上交由调用我的地方去处理。”
        if (!PKG_SYSTEMUI.equals(lpparam.packageName)) return;

        try{
            final Class<?> miuiStatusBarViewClazz = XposedHelpers.findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", lpparam.classLoader);
            //final 关键字表示该变量是一个不可变的常量（只能被赋值一次，赋值后不能再修改指向）
            XposedHelpers.findAndHookMethod(miuiStatusBarViewClazz, "onViewAttached" ,new XC_MethodHook(){// 拦截回调对象（一个匿名内部类）
                @Override
                protected  void afterHookedMethod(MethodHookParam param) throws Throwable{
                    //MethodHookParam param 是 Xposed 框架在拦截方法时传给你的上下文信息对象（包含被拦截方法的输入、输出以及宿主对象）
                    // 目标方法onViewAttached执行完后，会跑到这里执行以下代码
                    if (handler == null) {
                        handler = new Handler(Looper.getMainLooper());
                    }

                    //statusBarView = (View)XposedHelpers.getObjectField(param.thisObject, "mView");//(ViewGroup) param.thisObject;
                    Object controllerView = XposedHelpers.getObjectField(param.thisObject , "mView");//param.thisObject：被 Hook 方法所属的实例对象，就是当前正在执行 onViewAttached 方法的 PhoneStatusBarViewController 实例
                    //Xposed 提供的反射工具方法。它可以强行突破 private（私有）权限限制，直接把对象内部的 mView 提取出来
                    if (controllerView == null) {
                        XposedBridge.log("BatteryTemp: mView is null");
                        return;
                    }
                    if (controllerView instanceof ViewGroup){//检查变量 controllerView 里面保存的对象，到底是不是 ViewGroup（容器视图）或其子类（如 LinearLayout、RelativeLayout 等）。
                        ViewGroup statusBarView2 = (ViewGroup) controllerView;
                        statusBarView = statusBarView2 ;
                        systemUiContext = statusBarView.getContext();
                    }else {
                        XposedBridge.log("mView is not a ViewGroup, type:" + controllerView.getClass().getName());
                        return;
                    }

                    ViewGroup leftSideGroup = (ViewGroup) XposedHelpers.callMethod(statusBarView, "findViewById", 0x7f0a0884);
                    //调用 statusBarView 对象的 findViewById(int id) 方法，传入 ID 0x7f0a0884
                    if (leftSideGroup != null){
                        tempTextView = new TextView(systemUiContext);//在内存中新建一个文本控件对象，传入 SystemUI 的上下文环境（Context），告诉系统这个控件是属于 SystemUI 界面的
                        updateTextColorAndSize(statusBarView,tempTextView);
                        tempTextView.setPadding(0,0,10,0);
                        //ViewGroup linearLayout = (ViewGroup)leftSideGroup.getChildAt(0);
                        leftSideGroup.addView(tempTextView, 1);//把创建好的 tempTextView 放入 leftSideGroup 容器中，并精确指定它的排列顺序位置
                        XposedBridge.log("BatteryTemp DEBUG: TextView added to left side of status bar.");
                        startTempUpdate();
                    } else {
                        XposedBridge.log("BatterTemp DEBUG: Left side container with ID 0x7f0a0884 not found.");
                    }
                }
                    }
            );

            final Class<?> networkSpeedViewClazz = XposedHelpers.findClass("com.android.systemui.statusbar.views.NetworkSpeedView", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class, new XC_MethodHook(){//监听/拦截所有调用setTextColor的对象
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                    if (statusBarView == null || tempTextView == null)
                        return;

                    if (!(param.thisObject instanceof TextView))
                        return;

                    TextView tv = (TextView) param.thisObject;

                    if (tv == null) return; // 多加一层保险

                    // 判断 TextView 是否属于 NetworkSpeedView
                    if (isInsideNetworkSpeedView(tv, networkSpeedViewClazz)) {
                        int color = (int) param.args[0];
                        tempTextView.setTextColor(color);
                    }
                }
                    }
            );

        }catch (Throwable t){
            XposedBridge.log("BatterTemp DEBUG: hook failed: " + t );
        }
    }

    // 🔧 递归查找祖先是否是 NetworkSpeedView（关键修复），利用网速来变化颜色
    private boolean isInsideNetworkSpeedView(View view, Class<?> networkSpeedViewClazz) {
        if (view == null) return false;
        if (networkSpeedViewClazz == null) return false;

        ViewParent parent = view.getParent();
        while (parent != null) {
            if (networkSpeedViewClazz.isInstance(parent)) {
                return true;
            }
            // 必须确保 parent 是 View，否则不要继续递归
            if (!(parent instanceof View)) break;
            parent = parent.getParent();
        }
        return false;
    }

    private void startTempUpdate(){
        handler.post(new Runnable() {
            @Override
            public void run(){
                if (tempTextView != null && systemUiContext != null){
                 Intent intent = systemUiContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                 if (intent != null){
                     int tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                     int celsius = Math.round(tempTenth / 10.0f );

                     int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);

                     BatteryManager batteryManager = (BatteryManager) systemUiContext.getSystemService(Context.BATTERY_SERVICE);

                     int current = (int)XposedHelpers.callMethod(batteryManager, "getIntProperty", 2);//4实际上是百分比

                     float power = (float)voltage * (float)current / 1000000000.0f ;

                     String powerString;
                     if(power < 0){
                         powerString = String.format(" %.2fw" , power);
                     }else {
                         powerString = String.format(" %.2fw" , power);
                     }

                     String tempString = String.format(" %s℃ %s" , celsius, powerString);
                     tempTextView.setText(tempString);
                 }
                }

                handler.postDelayed(this, 2000);
            }
        });
    }
    //设置初始字体和颜色
    private void updateTextColorAndSize(ViewGroup parent, TextView targetTextView){
        TextView clockView = (TextView) XposedHelpers.callMethod(parent, "findViewById", 0x7f0a024f);
        if(clockView != null){
            targetTextView.setTextColor(clockView.getCurrentTextColor());
            float fontsize = clockView.getTextSize() / systemUiContext.getResources().getDisplayMetrics().scaledDensity;//用 px 除以 scaledDensity，即可逆向算出它在当前系统环境下的 sp 数值
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontsize);
        }else {
            targetTextView.setTextColor(0xFFFFFFFF);
            targetTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
    }

}
