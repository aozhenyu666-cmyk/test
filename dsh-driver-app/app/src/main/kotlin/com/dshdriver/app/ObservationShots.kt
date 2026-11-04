package com.dshdriver.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Single-frame callback/buffer lifecycle adapted from android-scroll-capture (MIT); see THIRD_PARTY_NOTICES. */
object ObservationShots {
    private var service = WeakReference<AccessibilityService>(null)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    fun connected(s: AccessibilityService?) { service=WeakReference(s) }
    fun capture(context: android.content.Context, id: String, result: (String?,String)->Unit) {
        if(Build.VERSION.SDK_INT<30) {result(null,"unsupported-api");return}
        val s=service.get() ?: run {result(null,"a11y-disconnected");return}
        val done=AtomicBoolean(false)
        fun finish(path: String?,status: String) {
            if(done.compareAndSet(false,true)) result(path,status)
            else if(path!=null) File(context.filesDir,"driver/$path").delete()
        }
        main.postDelayed({finish(null,"timeout")},1500)
        fun shot(attempt: Int) {
            if(done.get()) return
            runCatching {
                s.takeScreenshot(Display.DEFAULT_DISPLAY,io,object:AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        var software: Bitmap?=null
                        var hardware: Bitmap?=null
                        var scaled: Bitmap?=null
                        try {
                            hardware=Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer,screenshot.colorSpace)
                            software=hardware?.copy(Bitmap.Config.ARGB_8888,false)
                            val source=software ?: run {finish(null,"bitmap-unavailable");return}
                            val width=minOf(720,source.width)
                            scaled=Bitmap.createScaledBitmap(source,width,(source.height.toLong()*width/source.width).toInt().coerceAtLeast(1),true)
                            if(done.get()) return
                            val path="shots/$id.jpg"
                            val file=File(context.filesDir,"driver/$path").apply {parentFile?.mkdirs()}
                            val ok=file.outputStream().use { scaled!!.compress(Bitmap.CompressFormat.JPEG,60,it) }
                            if(ok) finish(path,"saved") else {file.delete();finish(null,"encode-failed")}
                        } catch (_:Exception) {finish(null,"capture-failed")}
                        finally {
                            scaled?.takeIf { it!==software }?.recycle();software?.recycle();hardware?.recycle()
                            screenshot.hardwareBuffer.close()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        if(attempt==0 && (errorCode==AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || errorCode==AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
                            main.postDelayed({shot(1)},300)
                        else finish(null,"system-error-$errorCode")
                    }
                })
            }.onFailure {finish(null,"permission-or-service-error")}
        }
        shot(0)
    }
}
