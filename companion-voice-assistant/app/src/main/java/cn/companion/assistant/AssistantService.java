package cn.companion.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AssistantService extends Service {
    static AssistantService live;
    interface Listener { void changed(); }
    static final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    record Message(boolean user, String text) {}
    static final List<Message> messages = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ModelClient model = new ModelClient();
    private ModelClient.Cancellation modelRequest;
    private final List<ModelClient.Turn> history = new ArrayList<>();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, speaking, listening, busy, requested;
    private volatile boolean destroyed;
    private int silenceCount;
    private volatile int generation;
    private long utteranceGeneration;
    String status = "随时可以开始", partial = "";
    private WindowManager windows;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlay, panel;
    private TextView orb, overlayStatus, overlayText;

    @Override public void onCreate() {
        super.onCreate(); live = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("voice", "伴随语音会话", NotificationManager.IMPORTANCE_LOW));
        tts = new TextToSpeech(this, result -> handler.post(() -> {
            if (destroyed) return;
            ttsReady = result == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(1.05f);
            }
            if (!ttsReady) setStatus("中文语音播报不可用；可在系统安装中文语音引擎");
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { handler.post(() -> { if (!destroyed && id.equals(String.valueOf(utteranceGeneration))) { speaking = false; finishTurn(); } }); }
            @Override public void onError(String id) { handler.post(() -> { if (!destroyed && id.equals(String.valueOf(utteranceGeneration))) { speaking = false; requested = false; setStatus("播报失败；请检查系统语音引擎后点按继续"); } }); }
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) { stopSelf(); return START_NOT_STICKY; }
        if (Settings.canDrawOverlays(this) && overlay == null) showOverlay();
        if (intent != null && intent.getBooleanExtra("voice", false)) startVoice();
        else setStatus(status);
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AssistantService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "voice").setSmallIcon(R.drawable.ic_companion)
            .setContentTitle("伴随 · 语音会话已开启").setContentText("仅在显示「正在听」时识别声音；点此回到助手")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "关闭助手", stop).build())
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void setStatus(String value) { status = value; refresh(); }
    private void refresh() {
        if (overlayStatus != null) overlayStatus.setText(status);
        if (orb != null) orb.setText(listening ? "听" : busy ? "想" : speaking ? "说" : "伴");
        if (overlayText != null) {
            String last = messages.isEmpty() ? "点「说话」，我就在这里。" : messages.get(messages.size() - 1).text();
            overlayText.setText(partial.isEmpty() ? last : partial);
        }
        for (Listener listener : listeners) listener.changed();
    }
    private void addMessage(boolean user, String text) {
        messages.add(new Message(user, text));
        while (messages.size() > 80) messages.remove(0);
        refresh();
    }
    void startVoice() {
        cancelCurrent(); requested = true; silenceCount = 0;
        handler.postDelayed(() -> { if (requested && !destroyed) listen(); }, 250);
    }
    void pause() { cancelCurrent(); requested = false; setStatus("已暂停 · 点说话继续"); }
    void clearConversation() { pause(); history.clear(); messages.clear(); refresh(); }
    private void cancelCurrent() {
        generation++; utteranceGeneration++; requested = false; busy = false; speaking = false; listening = false; partial = "";
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) tts.stop();
        if (modelRequest != null) modelRequest.cancel();
        model.cancel();
        if (overlay != null) overlay.setVisibility(View.VISIBLE);
    }
    private void listen() {
        if (destroyed || !requested || listening || speaking || busy) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { requested = false; setStatus("没有可用的语音识别服务；请安装/启用系统识别服务，或使用文字输入"); return; }
        try {
            if (recognizer == null) {
                if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                else recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(recognitionListener(generation));
            }
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true; partial = ""; setStatus("正在听 · 请说话"); recognizer.startListening(request);
        } catch (RuntimeException e) { listening = false; requested = false; setStatus("无法启动语音识别，请检查麦克风权限和识别服务"); }
    }
    private RecognitionListener recognitionListener(int epoch) {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { if (epoch == generation) silenceCount = 0; }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { if (epoch == generation && requested) setStatus("正在识别…"); }
            @Override public void onEvent(int eventType, Bundle params) { }
            @Override public void onPartialResults(Bundle result) {
                if (epoch != generation || !requested || destroyed) return;
                ArrayList<String> texts = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts != null && !texts.isEmpty()) { partial = texts.get(0); refresh(); }
            }
            @Override public void onResults(Bundle result) {
                if (epoch != generation || !requested || destroyed) return;
                listening = false; partial = "";
                ArrayList<String> texts = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty()) { speechError(SpeechRecognizer.ERROR_NO_MATCH); return; }
                silenceCount = 0; handle(texts.get(0));
            }
            @Override public void onError(int error) { if (epoch == generation) speechError(error); }
        };
    }
    private void speechError(int error) {
        listening = false; partial = "";
        if (!requested || destroyed) return;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (++silenceCount < 3) { int current = generation; setStatus("没听清，再说一次…"); handler.postDelayed(() -> { if (current == generation) listen(); }, 650); return; }
            requested = false; setStatus("一会儿没听到声音，已暂停 · 点说话继续"); return;
        }
        requested = false;
        setStatus(switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未开启，请在系统设置中授权";
            case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用；可使用文字输入";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务忙，请稍后点说话重试";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "识别服务缺少中文支持，请在系统语音设置中安装中文";
            default -> "语音识别暂不可用（" + error + "），请点说话重试或输入文字";
        });
    }
    void submitText(String text) {
        if (text == null || text.isBlank()) return;
        cancelCurrent(); handle(text.trim());
    }
    private void handle(String text) {
        addMessage(true, text);
        CommandParser.Command command = CommandParser.parse(text);
        switch (command.type()) {
            case STOP -> { requested = false; reply("已暂停聆听。", false); }
            case SCREEN -> understandScreen(command.argument());
            case CHAT -> askModel(text, null);
            default -> reply(PhoneActions.perform(this, command), false);
        }
    }
    void screenFromButton() { cancelCurrent(); addMessage(true, "看一下屏幕"); understandScreen("请解释当前屏幕的内容，并给我有用的下一步建议。"); }
    private void understandScreen(String question) {
        if (!ModelClient.configured(this)) { reply("请先配置支持图片的模型接口，再使用看屏幕。", false); return; }
        if (!getSharedPreferences("settings", 0).getBoolean("screen_consent", false)) { reply("请先在模型设置中开启「按指令发送截图」。截图会发送给你配置的服务商。", false); return; }
        PhoneAccessService access = PhoneAccessService.live;
        if (access == null) { reply("请先在首页开启「屏幕与导航」无障碍服务。", false); return; }
        busy = true; setStatus("正在截取当前屏幕…");
        int current = generation;
        if (overlay != null) overlay.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (destroyed || current != generation) { if (overlay != null) overlay.setVisibility(View.VISIBLE); return; }
            try {
                access.capture((jpeg, error) -> {
                    if (overlay != null) overlay.setVisibility(View.VISIBLE);
                    if (destroyed || current != generation) return;
                    if (error != null) reply(error, false); else askModel(question, jpeg);
                });
            } catch (RuntimeException e) { if (overlay != null) overlay.setVisibility(View.VISIBLE); if (current == generation) reply("截图权限已变化，请检查无障碍服务。", false); }
        }, 250);
    }
    private void askModel(String text, byte[] image) {
        if (!ModelClient.configured(this)) {
            reply("我已经可以执行手机指令，例如「打开微信」「返回桌面」。自由对话需要先在首页配置模型地址和模型名称。", false); return;
        }
        busy = true; setStatus(image == null ? "正在思考… · 可点说话打断" : "正在理解屏幕… · 可点说话打断");
        int current = generation;
        List<ModelClient.Turn> context = new ArrayList<>(history);
        ModelClient.Cancellation cancellation = new ModelClient.Cancellation(); modelRequest = cancellation;
        network.execute(() -> {
            if (destroyed || current != generation || cancellation.cancelled) return;
            String answer; boolean success;
            try { answer = model.complete(this, context, text, image, cancellation); success = true; }
            catch (SocketTimeoutException e) { answer = "模型响应超时，请检查网络后重试。"; success = false; }
            catch (IOException e) { answer = "无法连接模型服务，请检查网络和 HTTPS 接口地址。"; success = false; }
            catch (ModelClient.ModelError e) { answer = e.getMessage(); success = false; }
            catch (org.json.JSONException e) { answer = "模型响应格式不兼容，需要 Chat Completions 格式。"; success = false; }
            catch (Exception e) { answer = "模型请求失败，请检查模型配置和本机密钥库。"; success = false; }
            String result = answer; boolean ok = success;
            handler.post(() -> {
                if (destroyed || current != generation) return;
                if (ok) {
                    history.add(new ModelClient.Turn("user", image == null ? text : "[用户主动提供了一张屏幕截图] " + text));
                    history.add(new ModelClient.Turn("assistant", result));
                    while (history.size() > 16) { history.remove(0); history.remove(0); }
                } else requested = false;
                reply(result, true);
            });
        });
    }
    private void reply(String text, boolean fromModel) {
        busy = false; addMessage(false, text); partial = "";
        if (ttsReady) {
            speaking = true; setStatus("正在说 · 点说话可以打断");
            String cleaned = text.replaceAll("[*#`]", "");
            String id = String.valueOf(++utteranceGeneration);
            int result = tts.speak(cleaned.substring(0, Math.min(cleaned.length(), TextToSpeech.getMaxSpeechInputLength())), TextToSpeech.QUEUE_FLUSH, null, id);
            if (result == TextToSpeech.ERROR) { speaking = false; requested = false; setStatus("播报不可用，回复已显示在屏幕上"); }
        } else { speaking = false; finishTurn(); }
    }
    private void finishTurn() {
        if (requested && getSharedPreferences("settings", 0).getBoolean("continuous", true)) {
            int current = generation; setStatus("准备继续听…"); handler.postDelayed(() -> { if (current == generation) listen(); }, 600);
        } else { requested = false; setStatus("随时可以开始 · 点说话继续"); }
    }
    // Touch handling delegates taps to the standard TextView.performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
        windows = getSystemService(WindowManager.class);
        overlay = Ui.column(this); overlay.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        overlay.setBackground(Ui.shape(Ui.BG, Ui.dp(this, 24))); overlay.setElevation(Ui.dp(this, 12));
        orb = Ui.text(this, "伴", 20, Ui.BG); orb.setGravity(Gravity.CENTER); orb.setBackground(Ui.shape(Ui.MINT, Ui.dp(this, 24)));
        overlay.addView(orb, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        panel = Ui.column(this); panel.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), 0);
        overlayStatus = Ui.text(this, status, 12, Ui.MINT); Ui.add(panel, overlayStatus, 0);
        overlayText = Ui.text(this, "点「说话」，我就在这里。", 14, Ui.TEXT); overlayText.setMaxLines(5); Ui.add(panel, overlayText, 8);
        LinearLayout controls = new LinearLayout(this);
        Button talk = Ui.button(this, "说话 / 打断", true), pause = Ui.button(this, "暂停", false);
        controls.addView(talk, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 0.65f); gap.leftMargin = Ui.dp(this, 6); controls.addView(pause, gap); Ui.add(panel, controls, 12);
        talk.setOnClickListener(v -> startVoice()); pause.setOnClickListener(v -> pause());
        Button screen = Ui.button(this, "看一下屏幕", false); Ui.add(panel, screen, 7); screen.setOnClickListener(v -> screenFromButton());
        TextView close = Ui.text(this, "关闭助手", 12, Ui.MUTED); close.setGravity(Gravity.CENTER); close.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8)); close.setOnClickListener(v -> stopSelf()); Ui.add(panel, close, 0);
        overlay.addView(panel, new LinearLayout.LayoutParams(Ui.dp(this, 266), -2)); panel.setVisibility(View.GONE);
        overlayParams = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START; overlayParams.x = Ui.dp(this, 12); overlayParams.y = Ui.dp(this, 150);
        overlay.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (overlay == null || !overlay.isAttachedToWindow()) return;
            android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
            int x = Math.max(0, Math.min(Math.max(0, bounds.width() - overlay.getWidth()), overlayParams.x));
            int y = Math.max(0, Math.min(Math.max(0, bounds.height() - overlay.getHeight() - Ui.dp(this, 32)), overlayParams.y));
            if (x != overlayParams.x || y != overlayParams.y) {
                overlayParams.x = x; overlayParams.y = y;
                try { windows.updateViewLayout(overlay, overlayParams); } catch (RuntimeException ignored) { }
            }
        });
        orb.setContentDescription("伴随浮窗，点按展开，拖动移动位置");
        orb.setOnClickListener(v -> { panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); refresh(); });
        orb.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int x, y; boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> { startX = event.getRawX(); startY = event.getRawY(); x = overlayParams.x; y = overlayParams.y; moved = false; return true; }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                        if (Math.abs(dx) + Math.abs(dy) > Ui.dp(AssistantService.this, 8)) moved = true;
                        if (moved) {
                            android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
                            overlayParams.x = Math.max(0, Math.min(bounds.width() - overlay.getWidth(), x + (int) dx));
                            overlayParams.y = Math.max(0, Math.min(bounds.height() - overlay.getHeight(), y + (int) dy));
                            try { windows.updateViewLayout(overlay, overlayParams); } catch (RuntimeException ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); return true; }
                    default -> { return true; }
                }
            }
        });
        try { windows.addView(overlay, overlayParams); } catch (RuntimeException e) { overlay = null; orb = null; overlayStatus = null; overlayText = null; setStatus("浮窗未能显示，请检查悬浮窗权限"); }
    }
    @Override public void onDestroy() {
        destroyed = true; cancelCurrent(); handler.removeCallbacksAndMessages(null); network.shutdownNow();
        if (tts != null) tts.shutdown();
        if (overlay != null && windows != null) try { windows.removeView(overlay); } catch (RuntimeException ignored) { }
        live = null; for (Listener listener : listeners) listener.changed();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
