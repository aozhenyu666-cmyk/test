package com.yishou.app.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.yishou.app.YishouApp
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** 作答时附带的一张图片的状态。图片本身不保存，只保存识图模型转写出的文字。 */
sealed interface Attachment {
    data object Working : Attachment
    data class Ready(val text: String) : Attachment
    data class Failed(val message: String) : Attachment
}

/** 选图或拍照之后：压缩 → 交给识图模型转写 → 得到文字。每个作答页面各持有一个。 */
class AttachmentController(private val app: YishouApp, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<Attachment?>(null)
    val state: StateFlow<Attachment?> = _state.asStateFlow()
    private var job: Job? = null

    /** 转写好的文字；没有图片或还没好时为 null */
    val readyText: String? get() = (_state.value as? Attachment.Ready)?.text

    val busy: Boolean get() = _state.value is Attachment.Working

    fun attach(uri: Uri, taskTitle: String?, coachMove: String?) {
        job?.cancel()
        if (!app.settings.vision.value.isComplete) {
            _state.value = Attachment.Failed("还没有配置识图模型。到 设置 → 识图模型 里填写后才能用图片作答。")
            return
        }
        _state.value = Attachment.Working
        job = scope.launch {
            val dataUrl = try {
                withContext(Dispatchers.IO) { ImageEncoder.toDataUrl(app, uri) }
            } catch (e: Exception) {
                _state.value = Attachment.Failed("读取图片失败：${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            _state.value = when (val r = app.vision.transcribe(taskTitle, coachMove, dataUrl)) {
                is LlmResult.Ok -> Attachment.Ready(r.value)
                is LlmResult.Err -> Attachment.Failed("识图失败：${r.error.message}")
            }
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = null
    }
}

/** 把图片缩到长边不超过 1600 像素，压成 JPEG，转成 data URL。 */
object ImageEncoder {
    private const val MAX_SIDE = 1600

    fun toDataUrl(context: Context, uri: Uri): String {
        val bitmap = decode(context, uri)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会按照片的 EXIF 方向自动摆正
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("无法解码图片")
        val scale = MAX_SIDE.toFloat() / max(raw.width, raw.height)
        if (scale >= 1f) return raw
        val scaled = Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
        raw.recycle()
        return scaled
    }
}
