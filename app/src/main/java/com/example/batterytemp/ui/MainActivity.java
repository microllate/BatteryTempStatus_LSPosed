package com.example.batterytemp.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Battery Temp Status (LSPosed)\n作用域：仅 SystemUI\n开关：在 LSPosed 中勾选/取消即可。");
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
