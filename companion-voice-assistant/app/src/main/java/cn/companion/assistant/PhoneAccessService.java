package cn.companion.assistant;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.io.ByteArrayOutputStream;

public class PhoneAccessService extends AccessibilityService {
    static volatile PhoneAccessService live;
    interface CaptureCallback { void result(byte[] jpeg, String error); }
    @Override protected void onServiceConnected() { live = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No passive collection. */ }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (live == this) live = null; super.onDestroy(); }

    void capture(CaptureCallback callback) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hardware = null, bitmap = null, scaled = null;
                try {
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException();
                    bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    float ratio = Math.min(1f, 1280f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                    scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 78, bytes);
                    callback.result(bytes.toByteArray(), null);
                } catch (Exception e) { callback.result(null, "截图失败，请重新尝试。"); }
                finally {
                    if (scaled != null && scaled != bitmap) scaled.recycle();
                    if (bitmap != null) bitmap.recycle();
                    if (hardware != null) hardware.recycle();
                    buffer.close();
                }
            }
            @Override public void onFailure(int errorCode) {
                String error = switch (errorCode) {
                    case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图太频繁，请稍等一秒再试。";
                    case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "屏幕权限已关闭，请重新开启伴随的无障碍服务。";
                    case ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "系统无法截取当前页面，请切回目标应用后重试。";
                    default -> "无法截取这个页面。受保护的内容或锁屏可能不允许截图。";
                };
                callback.result(null, error);
            }
        });
    }
}
