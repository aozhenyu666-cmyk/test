package cn.companion.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(16, 19, 29), CARD = Color.rgb(27, 32, 47), MINT = Color.rgb(147, 232, 212), TEXT = Color.rgb(238, 242, 249), MUTED = Color.rgb(157, 168, 190);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    static TextView text(Context c, String value, int size, int color) {
        TextView view = new TextView(c); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(c, 3), 1); return view;
    }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static Button button(Context c, String title, boolean primary) {
        Button b = new Button(c); b.setText(title); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(primary ? BG : TEXT); b.setTypeface(null, Typeface.BOLD);
        b.setBackground(shape(primary ? MINT : Color.rgb(44, 51, 70), dp(c, 16)));
        b.setMinHeight(dp(c, 48)); b.setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7)); return b;
    }
    static void add(LinearLayout parent, android.view.View child, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(parent.getContext(), top); parent.addView(child, lp);
    }
    static TextView badge(Context c, String label) {
        TextView t = text(c, label, 12, MINT); t.setGravity(Gravity.CENTER); t.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5)); t.setBackground(shape(Color.rgb(32, 56, 56), dp(c, 20))); return t;
    }
    private Ui() {}
}
