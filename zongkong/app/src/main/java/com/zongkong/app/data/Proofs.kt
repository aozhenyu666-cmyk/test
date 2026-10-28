package com.zongkong.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.zongkong.core.DayClock
import com.zongkong.core.work.Delivery
import com.zongkong.core.work.ProofItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 交付记录（本机）：每次交了什么证据、谁判的。照片只存在手机里（filesDir/proofs），不上传 Notion。
 * 也用来查“这张照片交过没有”。
 */
class ProofStore(context: Context) {
    private val file = File(context.filesDir, "zk/deliveries.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Delivery.serializer())
    @Volatile private var cache: List<Delivery>? = null

    @Synchronized
    fun all(): List<Delivery> = cache ?: try {
        if (file.exists()) json.decodeFromString(ser, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w("ProofStore", "读交付记录失败", e)
        emptyList()
    }.also { cache = it }

    @Synchronized
    fun save(d: Delivery) {
        val list = (all() + d).takeLast(400)
        cache = list
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "deliveries.json.tmp")
            tmp.writeText(json.encodeToString(ser, list))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) {
            Log.w("ProofStore", "存交付记录失败", e)
        }
    }

    /** 照片指纹 → 交过的那一步的标题。 */
    fun usedPhotos(): Map<String, String> = all().flatMap { d -> d.items.filter { it.kind == ProofItem.PHOTO && it.hash.isNotBlank() }.map { it.hash to d.title } }.toMap()

    fun of(actionKey: String): Delivery? = all().lastOrNull { it.actionKey == actionKey }
}

/** 照片：存一份缩小的副本（保留拍摄时间）、算指纹、拼图给 AI 看。 */
object Photos {
    private const val TAG = "Photos"
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun dir(c: Context) = File(c.filesDir, "proofs").apply { mkdirs() }

    /** 拍照用的临时文件（相机写进去，再由 [fromFile] 处理）。 */
    fun cameraTarget(c: Context): File = File(dir(c), "raw_${System.currentTimeMillis()}.jpg")

    fun fromUri(c: Context, uri: Uri): ProofItem? = try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        fromBytes(c, bytes)
    } catch (e: Exception) {
        Log.w(TAG, "读不了这张图", e)
        null
    }

    fun fromFile(c: Context, f: File, deleteAfter: Boolean = false): ProofItem? = try {
        if (!f.exists() || f.length() == 0L) null else fromBytes(c, f.readBytes()).also { if (deleteAfter) f.delete() }
    } catch (e: Exception) {
        Log.w(TAG, "读不了这张图", e)
        null
    }

    /** 对话里已经存好的图（已经缩过、带着拍摄时间）：只补指纹和拍摄时间。 */
    fun fromStored(path: String): ProofItem? {
        val f = File(path)
        if (!f.exists()) return null
        return ProofItem(ProofItem.PHOTO, path, takenAt(f.inputStream()), sha(f.readBytes()))
    }

    private fun fromBytes(c: Context, bytes: ByteArray): ProofItem? {
        val taken = takenAt(bytes.inputStream())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = File(dir(c), "p_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        if (taken > 0) runCatching {
            ExifInterface(out.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, java.time.Instant.ofEpochMilli(taken).atZone(DayClock.BEIJING).toLocalDateTime().format(EXIF_TIME))
                saveAttributes()
            }
        }
        // 指纹按原图算：同一张图从相册再选一次也认得出
        return ProofItem(ProofItem.PHOTO, out.absolutePath, taken, sha(bytes))
    }

    fun takenAt(input: InputStream): Long = try {
        input.use { s ->
            val e = ExifInterface(s)
            val raw = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME)
            if (raw.isNullOrBlank()) 0L else LocalDateTime.parse(raw.trim(), EXIF_TIME).atZone(DayClock.BEIJING).toInstant().toEpochMilli()
        }
    } catch (e: Exception) {
        0L
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }

    /**
     * 最多 4 张拼成一张（2×2，长边 1600），一次发给 AI 看。只有一张就直接用它。没有照片返回空。
     */
    fun collage(c: Context, paths: List<String>): String {
        val ps = paths.filter { File(it).exists() }.take(4)
        if (ps.isEmpty()) return ""
        if (ps.size == 1) return ps[0]
        return try {
            val cols = 2
            val rows = (ps.size + 1) / 2
            val cell = 800
            val canvasBmp = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.RGB_565)
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            ps.forEachIndexed { i, p ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(p, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > cell * 2) sample *= 2
                val b = BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@forEachIndexed
                val scale = minOf(cell.toFloat() / b.width, cell.toFloat() / b.height)
                val w = (b.width * scale).toInt()
                val h = (b.height * scale).toInt()
                val x = (i % cols) * cell + (cell - w) / 2
                val y = (i / cols) * cell + (cell - h) / 2
                canvas.drawBitmap(b, null, Rect(x, y, x + w, y + h), paint)
                b.recycle()
            }
            val out = File(dir(c), "collage_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { canvasBmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            canvasBmp.recycle()
            out.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "拼图失败，只发第一张", e)
            ps[0]
        }
    }
}
