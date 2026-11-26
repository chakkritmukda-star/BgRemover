package com.example.bgremover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object BitmapStore {
    var editingOriginal: Bitmap? = null
    var editingCurrent: Bitmap? = null
    var editedResult: Bitmap? = null
    var hasBgRemoved: Boolean = false

    fun clearSession() {
        editingOriginal = null
        editingCurrent = null
        editedResult = null
    }
}

object AiEngine {

    enum class BgQuality(val assetName: String, val inputSize: Int) {
        FAST("model_fast.onnx", 320),
        HD("model_hd.onnx", 1024)
    }

    /** ผลลัพธ์ Magic Eraser: แพตช์ (ขอบนุ่มด้วย alpha) พร้อมตำแหน่งที่ต้องวางบนรูปเดิม */
    class InpaintPatch(val patch: Bitmap, val left: Int, val top: Int)

    /** ขนาดอินพุตของ MI-GAN (ตัวแปลง pipeline ส่วนใหญ่ fix ไว้ที่ 512) */
    private const val INPAINT_SIZE = 512

    /** ลอง XNNPACK (เร็วกว่า CPU ปกติบนหลายเครื่อง) ถ้าเครื่องไหนมีปัญหาให้ตั้งเป็น false */
    private const val USE_XNNPACK = false

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionMutex = Mutex()

    private var activeBgQuality: BgQuality? = null
    private var bgSession: OrtSession? = null
    private var inpaintSession: OrtSession? = null

    // ------------------------------------------------------------------
    // Warm-up: แตกไฟล์โมเดลไว้ล่วงหน้าเท่านั้น (ไม่เรียก Native Session เพื่อกันแอปเด้ง)
    // ------------------------------------------------------------------

    suspend fun warmUp(context: Context, quality: BgQuality = BgQuality.FAST) {
        withContext(Dispatchers.IO) {
            runCatching { ensureModelFile(context.applicationContext, quality.assetName) }
        }
    }

    suspend fun warmUpInpaint(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching { ensureModelFile(context.applicationContext, "migan.onnx") }
        }
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    private fun createOptions(): OrtSession.SessionOptions {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            var xnn = false
            if (USE_XNNPACK) {
                xnn = runCatching {
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }.isSuccess
            }
            // ถ้าใช้ XNNPACK ให้ thread หลักของ ORT เป็น 1 (XNNPACK จัดการ thread เอง)
            setIntraOpNumThreads(if (xnn) 1 else threads)
        }
    }

    private fun ensureModelFile(context: Context, assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        val assetLen = runCatching {
            context.assets.openFd(assetName).use { it.length }
        }.getOrDefault(-1L)

        if (!outFile.exists() || outFile.length() < 1024L || (assetLen > 0 && outFile.length() != assetLen)) {
            val tmpFile = File(context.filesDir, "$assetName.tmp")
            context.assets.open(assetName).use { input ->
                tmpFile.outputStream().buffered(256 * 1024).use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }
            if (outFile.exists()) outFile.delete()
            tmpFile.renameTo(outFile)
        }
        return outFile
    }

    private suspend fun getBgSession(context: Context, quality: BgQuality): OrtSession =
        sessionMutex.withLock {
            val existing = bgSession
            if (existing != null && activeBgQuality == quality) return@withLock existing
            runCatching { existing?.close() }
            bgSession = null

            val file = ensureModelFile(context, quality.assetName)
            createOptions().use { opts ->
                ortEnv.createSession(file.absolutePath, opts).also {
                    bgSession = it
                    activeBgQuality = quality
                }
            }
        }

    private suspend fun getInpaintSession(context: Context): OrtSession =
        sessionMutex.withLock {
            inpaintSession ?: run {
                val file = ensureModelFile(context, "migan.onnx")
                createOptions().use { opts ->
                    ortEnv.createSession(file.absolutePath, opts).also { inpaintSession = it }
                }
            }
        }

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    fun decodeSmartBitmap(context: Context, uri: Uri, maxEdge: Int = 1600): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxEdge * 1.5 || boundsOpts.outHeight / sampleSize > maxEdge * 1.5) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val longest = max(raw.width, raw.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest.toFloat() else 1f

        return if (rotation != 0f || scale < 1f) {
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0f) postRotate(rotation)
            }
            val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (transformed !== raw) raw.recycle()
            transformed
        } else {
            raw
        }
    }

    // ------------------------------------------------------------------
    // Remove background
    // ------------------------------------------------------------------

    suspend fun removeBackground(
        context: Context,
        src: Bitmap,
        quality: BgQuality
    ): Bitmap = withContext(Dispatchers.Default) {
        val session = getBgSession(context.applicationContext, quality)
        val size = quality.inputSize
        val plane = size * size

        // 1) Preprocess: ย่อ + LUT แทนการหาร/ลบทีละพิกเซล + ลูปเดียว
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(plane)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== src) scaled.recycle()

        val mean = if (quality == BgQuality.FAST) floatArrayOf(0.485f, 0.456f, 0.406f) else floatArrayOf(0.5f, 0.5f, 0.5f)
        val std = if (quality == BgQuality.FAST) floatArrayOf(0.229f, 0.224f, 0.225f) else floatArrayOf(1f, 1f, 1f)
        val lut = Array(3) { c -> FloatArray(256) { v -> (v / 255f - mean[c]) / std[c] } }

        val floatBuf = ByteBuffer.allocateDirect(3 * plane * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val lutR = lut[0]; val lutG = lut[1]; val lutB = lut[2]
        for (i in 0 until plane) {
            val p = px[i]
            floatBuf.put(i, lutR[(p shr 16) and 0xFF])
            floatBuf.put(plane + i, lutG[(p shr 8) and 0xFF])
            floatBuf.put(plane * 2 + i, lutB[p and 0xFF])
        }
        floatBuf.rewind()

        // 2) Inference
        val outMask = FloatArray(plane)
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(ortEnv, floatBuf, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result[0] as OnnxTensor
                val outFb = outTensor.floatBuffer
                outFb.rewind()
                outFb.get(outMask, 0, min(outFb.remaining(), plane))
            }
        }

        // 3) Normalize mask -> ALPHA_8 (ผ่านรอบเดียว)
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in outMask) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val needSigmoid = minV < -0.05f || maxV > 1.05f
        var lo = minV
        var hi = maxV
        if (needSigmoid) {
            lo = 1f / (1f + exp(-lo))
            hi = 1f / (1f + exp(-hi))
        }
        val inv = 255f / (hi - lo).coerceAtLeast(1e-6f)
        val alphaBytes = ByteArray(plane)
        for (i in 0 until plane) {
            val v = if (needSigmoid) 1f / (1f + exp(-outMask[i])) else outMask[i]
            alphaBytes[i] = ((v - lo) * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        val maskBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        maskBmp.copyPixelsFromBuffer(ByteBuffer.wrap(alphaBytes))

        // 4) Composite ด้วย Canvas (native) แทนลูปทุกพิกเซลที่ความละเอียดเต็ม
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBmp, null, Rect(0, 0, w, h), dstIn)
        maskBmp.recycle()
        out
    }

    // ------------------------------------------------------------------
    // Magic Eraser (local inpaint แบบ PhotoRoom)
    // ------------------------------------------------------------------

    /**
     * @param colorSrc   รูปสีปัจจุบัน (ไม่แก้ไขในฟังก์ชันนี้)
     * @param strokeMask รอยแปรง (alpha > 0 = บริเวณที่จะลบ) ขนาดเท่า colorSrc
     * @param bounds     กรอบของรอยแปรง (พิกัดรูป) ใช้แทนการสแกนทั้งภาพ
     * @return แพตช์ที่ blend ขอบแล้ว ให้ drawBitmap ลงที่ (left, top) ได้เลย
     */
    suspend fun magicErase(
        context: Context,
        colorSrc: Bitmap,
        strokeMask: Bitmap,
        bounds: Rect
    ): InpaintPatch = withContext(Dispatchers.Default) {
        val s = INPAINT_SIZE
        val area = s * s
        val w = colorSrc.width
        val h = colorSrc.height
        val minSide = min(w, h)

        // 1) สี่เหลี่ยมจัตุรัสรอบรอยแปรง (มี context รอบๆ ให้โมเดลเห็น)
        val wanted = max(bounds.width(), bounds.height()) * 2 + 96
        val side = wanted.coerceIn(min(256, minSide), minSide)
        val left = (bounds.centerX() - side / 2).coerceIn(0, w - side)
        val top = (bounds.centerY() - side / 2).coerceIn(0, h - side)
        val srcRect = Rect(left, top, left + side, top + side)
        val dstRect = Rect(0, 0, s, s)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 2) ย่อ/ขยายเป็น 512 ด้วย Canvas ครั้งเดียว (ไม่ต้อง crop แยกก่อน)
        val img = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(img).drawBitmap(colorSrc, srcRect, dstRect, paint)
        val msk = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(msk).drawBitmap(strokeMask, srcRect, dstRect, paint)

        val ip = IntArray(area)
        val mp = IntArray(area)
        img.getPixels(ip, 0, s, 0, 0, s, s)
        msk.getPixels(mp, 0, s, 0, 0, s, s)
        img.recycle()
        msk.recycle()

        // 3) ขยายรอยแปรงออก (เก็บเงา/ขอบวัตถุ) + เตรียม alpha ขอบนุ่ม
        val hole = ByteArray(area)
        for (i in 0 until area) if ((mp[i] ushr 24) > 10) hole[i] = 1
        val grown = dilate(hole, s, s, 6)

        val imgBuf = ByteBuffer.allocateDirect(3 * area).order(ByteOrder.nativeOrder())
        val maskBuf = ByteBuffer.allocateDirect(area).order(ByteOrder.nativeOrder())
        val feather = IntArray(area)
        for (i in 0 until area) {
            val c = ip[i]
            imgBuf.put(i, ((c shr 16) and 0xFF).toByte())
            imgBuf.put(area + i, ((c shr 8) and 0xFF).toByte())
            imgBuf.put(2 * area + i, (c and 0xFF).toByte())
            val isHole = grown[i].toInt() != 0
            maskBuf.put(i, if (isHole) 0.toByte() else 0xFF.toByte()) // 0 = รูที่ต้องเติม
            feather[i] = if (isHole) 255 else 0
        }
        boxBlur(feather, s, s, 3)

        // 4) Inference
        val session = getInpaintSession(context.applicationContext)
        val outBytes = runInpaintModel(session, imgBuf, maskBuf, s)

        // 5) ประกอบแพตช์ ARGB (alpha = feather) แล้วขยายกลับขนาดจริง
        val outPx = IntArray(area)
        for (i in 0 until area) {
            val r = outBytes[i].toInt() and 0xFF
            val g = outBytes[area + i].toInt() and 0xFF
            val b = outBytes[2 * area + i].toInt() and 0xFF
            outPx[i] = (feather[i] shl 24) or (r shl 16) or (g shl 8) or b
        }
        val patch512 = Bitmap.createBitmap(outPx, s, s, Bitmap.Config.ARGB_8888)
        val patch = if (side == s) patch512 else {
            Bitmap.createScaledBitmap(patch512, side, side, true).also {
                if (it !== patch512) patch512.recycle()
            }
        }
        InpaintPatch(patch, left, top)
    }

    private fun runInpaintModel(session: OrtSession, imgBuf: ByteBuffer, maskBuf: ByteBuffer, s: Int): ByteArray {
        imgBuf.rewind()
        maskBuf.rewind()
        val names = session.inputNames.toList()
        val imgName = names.firstOrNull { it.contains("image", ignoreCase = true) } ?: names[0]
        val maskName = names.firstOrNull { it.contains("mask", ignoreCase = true) } ?: names[1]
        val out = ByteArray(3 * s * s)
        val shapeImg = longArrayOf(1, 3, s.toLong(), s.toLong())
        val shapeMask = longArrayOf(1, 1, s.toLong(), s.toLong())
        OnnxTensor.createTensor(ortEnv, imgBuf, shapeImg, OnnxJavaType.UINT8).use { imgT ->
            OnnxTensor.createTensor(ortEnv, maskBuf, shapeMask, OnnxJavaType.UINT8).use { mT ->
                session.run(mapOf(imgName to imgT, maskName to mT)).use { res ->
                    val bb = (res[0] as OnnxTensor).byteBuffer
                    bb.rewind()
                    bb.get(out, 0, min(bb.remaining(), out.size))
                }
            }
        }
        return out
    }

    /** ขยายพื้นที่ค่า 1 ออก r พิกเซล (แยกแกน X/Y เร็วกว่าทำ 2 มิติตรงๆ มาก) */
    private fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (src[row + x].toInt() != 0) {
                    val x0 = max(0, x - r)
                    val x1 = min(w - 1, x + r)
                    for (nx in x0..x1) tmp[row + nx] = 1
                }
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (tmp[row + x].toInt() != 0) {
                    val y0 = max(0, y - r)
                    val y1 = min(h - 1, y + r)
                    for (ny in y0..y1) out[ny * w + x] = 1
                }
            }
        }
        return out
    }

    /** Box blur แยกแกน (running sum) ปรับค่าใน a โดยตรง */
    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += a[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / d
                sum += a[row + min(x + r + 1, w - 1)] - a[row + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / d
                sum += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
    }
}
// END OF AiEngine.kt
