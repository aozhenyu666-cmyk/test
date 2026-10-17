package cn.companion.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device smoke checks. Only fixture configuration and a fixture token are used. */
public class SmokeInstrumentation extends Instrumentation {
    private int passed;
    private boolean screenOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); screenOnly = arguments != null && "true".equals(arguments.getString("screen_only")); start(); }
    @Override public void onStart() {
        try {
            Context c = getTargetContext();
            Activity activity = startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> require(findText(activity.getWindow().getDecorView(), "开口，就有回应。"), "home screen rendered"));
            check("home screen rendered");

            if (!screenOnly) {
                String fixture = "fixture-token-not-a-real-key-8316";
                SecretStore.save(c, fixture);
                require(fixture.equals(SecretStore.read(c)), "Keystore round trip");
                require(!c.getSharedPreferences("secrets", 0).getString("token", "").contains(fixture), "stored token encrypted");
                SecretStore.save(c, ""); check("Keystore encrypted round trip");

                runOnMainSync(() -> c.startForegroundService(new Intent(c, AssistantService.class)));
                await(() -> AssistantService.live != null, "service started"); check("foreground service started");
                runOnMainSync(() -> AssistantService.live.submitText("你好"));
                waitForIdleSync();
                runOnMainSync(() -> require(lastMessage().contains("配置模型"), "unconfigured model fallback")); check("unconfigured model fallback");

                runOnMainSync(() -> AssistantService.live.submitText("打开不存在的应用8842"));
                runOnMainSync(() -> require(lastMessage().contains("没有找到"), "missing app feedback")); check("missing app feedback");

                runOnMainSync(() -> AssistantService.live.submitText("打开设置"));
                runOnMainSync(() -> require(lastMessage().contains("已打开系统设置"), "settings intent")); check("settings intent");

                c.getSharedPreferences("settings", 0).edit().putString("endpoint", "https://example.invalid/v1").putString("model", "fixture").putBoolean("screen_consent", false).commit();
                runOnMainSync(() -> AssistantService.live.screenFromButton());
                runOnMainSync(() -> require(lastMessage().contains("按指令发送截图"), "screen consent blocks capture/upload")); check("screen consent blocks capture/upload");
                c.getSharedPreferences("settings", 0).edit().remove("endpoint").remove("model").putBoolean("screen_consent", false).commit();
            } else {
                runOnMainSync(() -> c.startForegroundService(new Intent(c, AssistantService.class)));
                await(() -> AssistantService.live != null, "service started"); check("foreground service started");
            }

            await(() -> PhoneAccessService.live != null, "accessibility enabled by test setup");
            CountDownLatch captured = new CountDownLatch(1); AtomicReference<String> captureError = new AtomicReference<>();
            runOnMainSync(() -> PhoneAccessService.live.capture((bytes, error) -> {
                if (error != null) captureError.set(error);
                else {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (options.outWidth <= 0 || options.outHeight <= 0 || Math.max(options.outWidth, options.outHeight) > 1280) captureError.set("invalid JPEG dimensions");
                }
                captured.countDown();
            }));
            require(captured.await(30, TimeUnit.SECONDS), "screenshot callback timeout");
            require(captureError.get() == null, "screenshot failed: " + captureError.get()); check("accessibility screenshot encoded and bounded");

            runOnMainSync(() -> c.stopService(new Intent(c, AssistantService.class)));
            await(() -> AssistantService.live == null, "service stopped"); check("service shutdown");
            Bundle result = new Bundle(); result.putString("result", passed + " device checks passed"); finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Bundle result = new Bundle(); result.putString("failure", failure.toString()); result.putInt("passed", passed); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void check(String name) { passed++; Bundle b = new Bundle(); b.putString("check", name); b.putInt("passed", passed); sendStatus(0, b); }
    private static String lastMessage() { return AssistantService.messages.get(AssistantService.messages.size() - 1).text(); }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private interface Condition { boolean get(); }
    private void await(Condition condition, String label) {
        long limit = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < limit) {
            final boolean[] ok = {false}; runOnMainSync(() -> ok[0] = condition.get()); if (ok[0]) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(label);
    }
    private static boolean findText(View v, String text) {
        if (v instanceof TextView t && text.contentEquals(t.getText())) return true;
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (findText(group.getChildAt(i), text)) return true;
        return false;
    }
}
