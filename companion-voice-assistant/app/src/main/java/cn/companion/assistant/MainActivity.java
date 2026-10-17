package cn.companion.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements AssistantService.Listener {
    private TextView status, permissionStatus, modelStatus;
    private LinearLayout conversation;
    private ScrollView scroll;
    private EditText input;
    private String pendingText;
    private boolean pendingVoice;
    private int renderedHash;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        scroll = new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout content = Ui.column(this); content.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 32)); scroll.addView(content);

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = Ui.text(this, "伴随", 25, Ui.TEXT); brand.setTypeface(null, Typeface.BOLD); header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = Ui.badge(this, "ANDROID · 原型 0.1"); header.addView(version); Ui.add(content, header, 0);
        TextView title = Ui.text(this, "开口，就有回应。", 32, Ui.TEXT); title.setTypeface(null, Typeface.BOLD); Ui.add(content, title, 32);
        Ui.add(content, Ui.text(this, "陪你对话，也帮你操作手机。\n切换应用时，伴随依然在你身边。", 15, Ui.MUTED), 10);

        LinearLayout hero = card();
        TextView wave = Ui.text(this, "▂ ▅ ▃ █ ▆ ▃ ▂", 28, Ui.MINT); wave.setSingleLine(true); wave.setGravity(Gravity.CENTER); Ui.add(hero, wave, 6);
        status = Ui.text(this, "随时可以开始", 16, Ui.TEXT); status.setGravity(Gravity.CENTER); Ui.add(hero, status, 15);
        Button start = Ui.button(this, "开始说话", true); Ui.add(hero, start, 18); start.setOnClickListener(v -> ensureAssistant(true, null));
        LinearLayout actionRow = new LinearLayout(this);
        Button floating = Ui.button(this, "开启浮窗", false), pause = Ui.button(this, "暂停", false);
        actionRow.addView(floating, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.leftMargin = Ui.dp(this, 8); actionRow.addView(pause, p); Ui.add(hero, actionRow, 8);
        floating.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) openOverlaySettings(); else ensureAssistant(false, null);
        });
        pause.setOnClickListener(v -> { if (AssistantService.live != null) AssistantService.live.pause(); });
        Ui.add(hero, Ui.text(this, "说话时可点「开始说话」打断回复。\n首次使用需要麦克风和系统中文识别服务。", 12, Ui.MUTED), 12);
        Ui.add(content, hero, 24);

        LinearLayout abilities = card();
        Ui.add(abilities, heading("让伴随准备好"), 0);
        permissionStatus = Ui.text(this, "", 13, Ui.MUTED); Ui.add(abilities, permissionStatus, 10);
        Button overlay = Ui.button(this, "悬浮窗权限", false); Ui.add(abilities, overlay, 12); overlay.setOnClickListener(v -> openOverlaySettings());
        Button access = Ui.button(this, "屏幕与导航权限", false); Ui.add(abilities, access, 8);
        access.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("屏幕与导航")
            .setMessage("开启系统无障碍服务后，伴随可以执行你明确说出的返回、桌面和最近任务指令，并在你主动说「看一下屏幕」时截图。\n\n不会后台巡视，也不提供自动点击。截图上传还需要在模型设置中单独开启。\n\n在接下来的系统页面中，找到「伴随 · 屏幕与导航」。部分手机需要先到应用信息允许受限设置。")
            .setPositiveButton("去系统设置", (d, which) -> safeLaunch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("稍后", null).show());
        Switch continuous = new Switch(this); continuous.setText("连续对话 · 回复后继续听"); continuous.setTextColor(Ui.TEXT); continuous.setTextSize(14);
        continuous.setChecked(getSharedPreferences("settings", 0).getBoolean("continuous", true));
        continuous.setOnCheckedChangeListener((button, checked) -> getSharedPreferences("settings", 0).edit().putBoolean("continuous", checked).apply()); Ui.add(abilities, continuous, 16);
        Ui.add(abilities, Ui.text(this, "这是轮流对话：识别 → 回复 → 继续听。连续三次没有听到内容会暂停。当前版本通过点按打断，不支持全天候唤醒词。", 12, Ui.MUTED), 8);
        Ui.add(content, abilities, 16);

        LinearLayout model = card();
        Ui.add(model, heading("接上你的 AI"), 0);
        modelStatus = Ui.text(this, "", 13, Ui.MUTED); Ui.add(model, modelStatus, 8);
        Button configure = Ui.button(this, "模型与隐私设置", false); Ui.add(model, configure, 12); configure.setOnClickListener(v -> configureModel());
        Ui.add(content, model, 16);

        Ui.add(content, heading("试着这样说"), 26);
        LinearLayout examples = new LinearLayout(this);
        for (String example : new String[]{"打开微信", "返回桌面"}) {
            Button b = Ui.button(this, example, false); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(0, 0, Ui.dp(this, 6), 0); examples.addView(b, lp);
            b.setOnClickListener(v -> ensureAssistant(false, example));
        }
        Ui.add(content, examples, 12);
        Button screenshot = Ui.button(this, "看一下屏幕", false); Ui.add(content, screenshot, 8); screenshot.setOnClickListener(v -> ensureAssistant(false, "看一下屏幕"));
        Ui.add(content, Ui.text(this, "想理解其他 App，请切过去后从浮窗发出看屏幕指令。", 12, Ui.MUTED), 8);

        LinearLayout chatHeader = new LinearLayout(this); chatHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView chatTitle = heading("这次对话"); chatHeader.addView(chatTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = Ui.text(this, "清空", 13, Ui.MINT); clear.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), 0, Ui.dp(this, 12)); chatHeader.addView(clear);
        clear.setOnClickListener(v -> { if (AssistantService.live != null) AssistantService.live.clearConversation(); else { AssistantService.messages.clear(); changed(); } }); Ui.add(content, chatHeader, 22);
        conversation = Ui.column(this); Ui.add(content, conversation, 8);
        input = field("也可以打字：打开应用，或问一个问题", false); input.setSingleLine(false); input.setMaxLines(4); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); Ui.add(content, input, 12);
        Button send = Ui.button(this, "发送", true); Ui.add(content, send, 8); send.setOnClickListener(v -> {
            String text = input.getText().toString().trim(); if (!text.isEmpty()) { ensureAssistant(false, text); input.setText(""); }
        });
        TextView close = Ui.text(this, "关闭助手与浮窗", 14, Ui.MUTED); close.setGravity(Gravity.CENTER); close.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 12));
        close.setOnClickListener(v -> stopService(new Intent(this, AssistantService.class))); Ui.add(content, close, 8);
        Ui.add(content, Ui.text(this, "基础指令在本机解析；系统语音识别可能使用网络。自由对话会向你配置的模型服务发送文本和最近 8 轮对话。截图仅在你授权并主动触发时发送。没有持久化聊天或截图。", 11, Ui.MUTED), 8);
        setContentView(root); root.requestApplyInsets(); changed();
    }
    private LinearLayout card() {
        LinearLayout l = Ui.column(this); l.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18)); l.setBackground(Ui.shape(Ui.CARD, Ui.dp(this, 22))); return l;
    }
    private TextView heading(String text) { TextView t = Ui.text(this, text, 18, Ui.TEXT); t.setTypeface(null, Typeface.BOLD); return t; }
    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.MUTED); e.setTextSize(14);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10)); e.setBackground(Ui.shape(Ui.CARD, Ui.dp(this, 12)));
        e.setSingleLine(true); e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); return e;
    }
    private void ensureAssistant(boolean voice, String text) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingText = text; pendingVoice = voice; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        Intent request = new Intent(this, AssistantService.class).putExtra("voice", voice);
        try { startForegroundService(request); }
        catch (RuntimeException e) { Toast.makeText(this, "系统未允许启动语音服务，请回到应用后重试。", Toast.LENGTH_LONG).show(); return; }
        if (text != null) deliverText(text, 0);
    }
    private void deliverText(String text, int attempt) {
        getWindow().getDecorView().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (AssistantService.live != null) AssistantService.live.submitText(text);
            else if (attempt < 10) deliverText(text, attempt + 1);
            else Toast.makeText(this, "助手启动失败，请检查权限后重试。", Toast.LENGTH_LONG).show();
        }, 100);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ensureAssistant(pendingVoice, pendingText);
            else Toast.makeText(this, "语音会话需要麦克风权限。可在应用信息的权限页面重新开启。", Toast.LENGTH_LONG).show();
            pendingText = null;
        }
        changed();
    }
    private void safeLaunch(Intent intent) {
        try { startActivity(intent); } catch (RuntimeException e) { Toast.makeText(this, "请在系统设置中手动找到伴随的对应权限。", Toast.LENGTH_LONG).show(); }
    }
    private void openOverlaySettings() { safeLaunch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }

    private void configureModel() {
        var prefs = getSharedPreferences("settings", 0);
        LinearLayout fields = Ui.column(this); fields.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        Ui.add(fields, Ui.text(this, "使用兼容 Chat Completions 的 HTTPS 接口。看屏幕需要模型支持图片。服务商可能计费。", 13, Ui.MUTED), 0);
        Ui.add(fields, Ui.text(this, "接口基础地址（通常以 /v1 结尾）", 12, Ui.MINT), 14);
        EditText endpoint = field("https://你的服务商/v1", false); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); endpoint.setText(prefs.getString("endpoint", "")); Ui.add(fields, endpoint, 6);
        Ui.add(fields, Ui.text(this, "模型名称", 12, Ui.MINT), 12);
        EditText model = field("填写服务商提供的模型 ID", false); model.setText(prefs.getString("model", "")); Ui.add(fields, model, 6);
        Ui.add(fields, Ui.text(this, "API Key（保存在本机密钥库中）", 12, Ui.MINT), 12);
        EditText key = field("无鉴权网关可留空", true);
        try { key.setText(SecretStore.read(this)); } catch (Exception e) { Toast.makeText(this, "原密钥无法解密，请重新填写。", Toast.LENGTH_LONG).show(); }
        Ui.add(fields, key, 6);
        CheckBox consent = new CheckBox(this); consent.setText("按指令发送截图：仅在我主动要求看屏幕时，将当前屏幕发送给以上服务商"); consent.setTextColor(Ui.TEXT); consent.setTextSize(13); consent.setChecked(prefs.getBoolean("screen_consent", false)); Ui.add(fields, consent, 14);
        Ui.add(fields, Ui.text(this, "不开启此项也能正常聊天。关闭后不会截取屏幕。文本和截图的服务端留存取决于你选择的服务商。", 12, Ui.MUTED), 8);
        ScrollView container = new ScrollView(this); container.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("模型与隐私设置").setView(container).setPositiveButton("保存", null).setNegativeButton("取消", null)
            .setNeutralButton("清除配置", (d, which) -> {
                try { SecretStore.save(this, ""); prefs.edit().remove("endpoint").remove("model").putBoolean("screen_consent", false).apply(); if (AssistantService.live != null) AssistantService.live.clearConversation(); changed(); }
                catch (Exception e) { Toast.makeText(this, "清除失败，请重试。", Toast.LENGTH_LONG).show(); }
            }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = endpoint.getText().toString().trim(), modelName = model.getText().toString().trim();
            String error = ModelClient.validateEndpoint(address);
            if (error != null) { endpoint.setError(error); return; }
            if (modelName.isEmpty()) { model.setError("请填写模型名称"); return; }
            try {
                if (AssistantService.live != null) AssistantService.live.clearConversation();
                SecretStore.save(this, key.getText().toString().trim());
                prefs.edit().putString("endpoint", address).putString("model", modelName).putBoolean("screen_consent", consent.isChecked()).apply();
                dialog.dismiss(); changed();
            } catch (Exception e) { key.setError("系统密钥库保存失败，请重试"); }
        })); dialog.show();
    }
    @Override protected void onStart() { super.onStart(); AssistantService.listeners.add(this); changed(); }
    @Override protected void onResume() { super.onResume(); changed(); }
    @Override protected void onStop() { AssistantService.listeners.remove(this); super.onStop(); }
    @Override public void changed() {
        if (status == null) return;
        AssistantService service = AssistantService.live;
        status.setText(service == null ? "随时可以开始" : (service.partial.isEmpty() ? service.status : service.partial));
        permissionStatus.setText(getString(R.string.permission_status,
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "✓" : "待开启",
            Settings.canDrawOverlays(this) ? "✓" : "待开启", PhoneAccessService.live != null ? "✓" : "未开启（可选）"));
        modelStatus.setText(ModelClient.configured(this) ? "模型已配置 · " + getSharedPreferences("settings", 0).getString("model", "") : "尚未连接模型。基础手机指令已经准备好。\n连接后，可自由聊天并按需理解屏幕。");
        int hash = AssistantService.messages.hashCode(); if (hash == renderedHash && conversation.getChildCount() > 0) return; renderedHash = hash;
        conversation.removeAllViews();
        if (AssistantService.messages.isEmpty()) { Ui.add(conversation, Ui.text(this, "对话将出现在这里。\n你可以说：「打开微信」，或聊聊今天的事。", 14, Ui.MUTED), 4); return; }
        for (AssistantService.Message message : AssistantService.messages) {
            LinearLayout bubble = Ui.column(this); bubble.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            bubble.setBackground(Ui.shape(message.user() ? 0xff253b3e : Ui.CARD, Ui.dp(this, 16)));
            Ui.add(bubble, Ui.text(this, message.user() ? "你" : "伴随", 11, Ui.MINT), 0);
            TextView body = Ui.text(this, message.text(), 15, Ui.TEXT); body.setTextIsSelectable(true); Ui.add(bubble, body, 4); Ui.add(conversation, bubble, 8);
        }
    }
}
