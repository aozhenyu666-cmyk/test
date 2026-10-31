package com.yishou.gatefixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 只装进测试模拟器，模拟娱乐或学习应用，不随一手发布。 */
public class ReadingActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText("外部学习页面仍然可见");
        label.setTextSize(22);
        box.addView(label);
        EditText edit = new EditText(this);
        edit.setHint("在这里打开输入法");
        box.addView(edit);
        setContentView(box);
    }
}
