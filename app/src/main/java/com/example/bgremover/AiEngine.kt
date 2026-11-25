package com.example.bgremover

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import kotlin.math.roundToInt

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

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionMutex = Mutex()

    private var fastBgSession: OrtSession? = null
    private var hdBgSession: OrtSession? = null
    private var inpaintSession: OrtSession? = null

    suspend fun warmUp(context: Context, quality: BgQuality = BgQuality.FAST) {
        withContext(Dispatchers.IO) {
            runCatching { getBgSession(context.applicationContext, quality) }
            runCatching { getInpaintSession(context.applicationContext) }
        }
    }

    private fun createOptimizedOptions(): OrtSession.SessionOptions {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val xnnThreads = (cores - 1).coerceAtLeast(2)
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            runCatching {
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addXnnpack(mapOf("intra_op_num_threads" to xnnThreads.toString()))
                setIntraOpNumThreads(1)
            }.onFailure {
                setIntraOpNumThreads(xnnThreads)
            }
        }
    }

    private fun ensureModelFile(context: Context, assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        val assetLen = runCatching {
            context.assets.openFd(assetName).use { it.length }
        }.getOrDefault(-1L)

        if (!outFile.exists() || outFile.length() < 1024L || (assetLen > 0 && outFile.length() != assetLen)) {
            context.assets.open(assetName).use { input ->
                outFile.outputStream().buffered(256 * 1024).use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }
        }
        return outFile
    }

    private suspend fun getBgSession(context: Context, quality: BgQuality): OrtSession =
        sessionMutex.withLock {
            when (quality) {
                BgQuality.FAST -> fastBgSession ?: run {
                    val file = ensureModelFile(context, quality.assetName)
                    ortEnv.createSession(file.absolutePath, createOptimizedOptions()).also {
                        fastBgSession = it
                    }
                }
                BgQuality.HD -> hdBgSession ?: run {
                    val file = ensureModelFile(context, quality.assetName)
                    ortEnv.createSession(file.absolutePath, createOptimizedOptions()).also {
                        hdBgSession = it
                    }
                }
            }
        }

    private suspend fun getInpaintSession(context: Context): OrtSession =
        sessionMutex.withLock {
            inpaintSession ?: run {
                val file = ensureModelFile(context, "migan.onnx")
                ortEnv.createSession(file.absolutePath, createOptimizedOptions()).also {
                    inpaintSession = it
                }
            }
        }

    fun decodeSmartBitmap(context: Context, uri: Uri, maxEdge: Int = 2048): Bitmap? {
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
            transformed.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            raw
        }
    }

    suspend fun removeBackground(
        context: Context,
        src: Bitmap,
        quality: BgQuality
    ): Bitmap = withContext(Dispatchers.Default) {
        val session = getBgSession(context.applicationContext, quality)
        val size = quality.inputSize
        val plane = size * size

        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(plane)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== src) scaled.recycle()

        val floatBuf = ByteBuffer.allocateDirect(3 * plane * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        if (quality == BgQuality.FAST) {
            for (i in 0 until plane) {
                val r = ((px[i] shr 16) and 0xFF) / 255f
                floatBuf.put(i, (r - 0.485f) / 0.229f)
            }
            for (i in 0 until plane) {
                val g = ((px[i] shr 8) and 0xFF) / 255f
                floatBuf.put(plane + i, (g - 0.456f) / 0.224f)
            }
            for (i in 0 until plane) {
                val b = (px[i] and 0xFF) / 255f
                floatBuf.put(plane * 2 + i, (b - 0.406f) / 0.225f)
            }
        } else {
            for (i in 0 until plane) {
                floatBuf.put(i, (((px[i] shr 16) and 0xFF) / 255f) - 0.5f)
            }
            for (i in 0 until plane) {
                floatBuf.put(plane + i, (((px[i] shr 8) and 0xFF) / 255f) - 0.5f)
            }
            for (i in 0 until plane) {
                floatBuf.put(plane * 2 + i, ((px[i] and 0xFF) / 255f) - 0.5f)
            }
        }
        floatBuf.rewind()

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

        var needSigmoid = false
        for (v in outMask) {
            if (v < -0.05f || v > 1.05f) {
                needSigmoid = true
                break
            }
        }
        if (needSigmoid) {
            for (i in outMask.indices) {
                outMask[i] = 1f / (1f + exp(-outMask[i]))
            }
        }

        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in outMask) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = (maxV - minV).coerceAtLeast(1e-6f)

        val maskSmall = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maskSmallPx = IntArray(plane)
        for (i in 0 until plane) {
            val norm = ((outMask[i] - minV) / range).coerceIn(0f, 1f)
            val a = (norm * 255f).roundToInt().coerceIn(0, 255)
            maskSmallPx[i] = a shl 24
        }
        maskSmall.setPixels(maskSmallPx, 0, size, 0, 0, size, size)

        val w = src.width
        val h = src.height
        val maskFull = Bitmap.createScaledBitmap(maskSmall, w, h, true)
        if (maskFull !== maskSmall) maskSmall.recycle()

        val srcPx = IntArray(w * h)
        val alphaPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        maskFull.getPixels(alphaPx, 0, w, 0, 0, w, h)
        maskFull.recycle()

        for (i in srcPx.indices) {
            val origA = (srcPx[i] ushr 24) and 0xFF
            val maskA = (alphaPx[i] ushr 24) and 0xFF
            val finalA = (origA * maskA) / 255
            srcPx[i] = (finalA shl 24) or (srcPx[i] and 0x00FFFFFF)
        }

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(srcPx, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Magic Eraser แบบ Photoroom ด้วยโมเดล MI-GAN Pipeline v2
     * ลบวัตถุบริเวณที่ระบายใน strokeBitmap แล้วเติมเนื้อภาพรอบข้างให้เนียนอัตโนมัติ
     */
    suspend fun magicEraseInpaint(
        context: Context,
        colorSrc: Bitmap,
        strokeBitmap: Bitmap
    ): Bitmap = withContext(Dispatchers.Default) {
        val w = colorSrc.width
        val h = colorSrc.height
        val strokePx = IntArray(w * h)
        strokeBitmap.getPixels(strokePx, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (((strokePx[row + x] ushr 24) and 0xFF) > 20) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return@withContext colorSrc.copy(Bitmap.Config.ARGB_8888, true)

        // ขยายขอบเขตบริบทรอบจุดที่ระบายเพื่อให้ AI เข้าใจพื้นหลังรอบข้างได้ดีที่สุด
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        val pad = max(max(boxW, boxH), 192)
        var cropLeft = (minX - pad).coerceAtLeast(0)
        var cropTop = (minY - pad).coerceAtLeast(0)
        var cropRight = (maxX + pad).coerceAtMost(w)
        var cropBottom = (maxY + pad).coerceAtMost(h)

        // ให้ขนาด Crop ขั้นต่ำใกล้เคียง 512px (ถ้ารูปใหญ่พอ)
        val minCrop = 512
        if (cropRight - cropLeft < minCrop && w >= minCrop) {
            val extra = (minCrop - (cropRight - cropLeft)) / 2
            cropLeft = (cropLeft - extra).coerceAtLeast(0)
            cropRight = (cropLeft + minCrop).coerceAtMost(w)
            cropLeft = (cropRight - minCrop).coerceAtLeast(0)
        }
        if (cropBottom - cropTop < minCrop && h >= minCrop) {
            val extra = (minCrop - (cropBottom - cropTop)) / 2
            cropTop = (cropTop - extra).coerceAtLeast(0)
            cropBottom = (cropTop + minCrop).coerceAtMost(h)
            cropTop = (cropBottom - minCrop).coerceAtLeast(0)
        }

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        val cropColor = Bitmap.createBitmap(colorSrc, cropLeft, cropTop, cropW, cropH)
        val cropMaskBmp = Bitmap.createBitmap(strokeBitmap, cropLeft, cropTop, cropW, cropH)

        // จำกัดขนาดส่งเข้าโมเดลไม่เกิน 1024px เพื่อความรวดเร็วระดับเสี้ยววินาที
        val maxInferEdge = 1024
        val inferScale = if (max(cropW, cropH) > maxInferEdge) {
            maxInferEdge.toFloat() / max(cropW, cropH).toFloat()
        } else 1f

        val inferW = (cropW * inferScale).roundToInt().coerceAtLeast(64)
        val inferH = (cropH * inferScale).roundToInt().coerceAtLeast(64)

        val inferColor = if (inferScale < 1f) Bitmap.createScaledBitmap(cropColor, inferW, inferH, true) else cropColor
        val inferStroke = if (inferScale < 1f) Bitmap.createScaledBitmap(cropMaskBmp, inferW, inferH, true) else cropMaskBmp

        val plane = inferW * inferH
        val inPx = IntArray(plane)
        val mPx = IntArray(plane)
        inferColor.getPixels(inPx, 0, inferW, 0, 0, inferW, inferH)
        inferStroke.getPixels(mPx, 0, inferW, 0, 0, inferW, inferH)

        // ขยายขอบ Mask (Dilation 5px) เพื่อเก็บเงาและขอบวัตถุไม่ให้เหลือคราบตกค้าง
        val holeMask = BooleanArray(plane)
        val dilateRadius = 5
        for (y in 0 until inferH) {
            val row = y * inferW
            for (x in 0 until inferW) {
                if (((mPx[row + x] ushr 24) and 0xFF) > 20) {
                    val y0 = (y - dilateRadius).coerceAtLeast(0)
                    val y1 = (y + dilateRadius).coerceAtMost(inferH - 1)
                    val x0 = (x - dilateRadius).coerceAtLeast(0)
                    val x1 = (x + dilateRadius).coerceAtMost(inferW - 1)
                    for (ny in y0..y1) {
                        val nrow = ny * inferW
                        for (nx in x0..x1) {
                            holeMask[nrow + nx] = true
                        }
                    }
                }
            }
        }

        val imgBuf = ByteBuffer.allocateDirect(3 * plane).order(ByteOrder.nativeOrder())
        val maskBuf = ByteBuffer.allocateDirect(plane).order(ByteOrder.nativeOrder())

        for (i in 0 until plane) {
            imgBuf.put(i, ((inPx[i] shr 16) and 0xFF).toByte())
            imgBuf.put(plane + i, ((inPx[i] shr 8) and 0xFF).toByte())
            imgBuf.put(plane * 2 + i, (inPx[i] and 0xFF).toByte())
            // MI-GAN contract: 255 (-1 signed byte) = เก็บภาพเดิม, 0 = บริเวณที่ต้องลบและเติมใหม่
            maskBuf.put(i, if (holeMask[i]) 0.toByte() else 0xFF.toByte())
        }
        imgBuf.rewind()
        maskBuf.rewind()

        val session = getInpaintSession(context.applicationContext)
        val names = session.inputNames.toList()
        val imgInputName = names.firstOrNull { it.contains("image", ignoreCase = true) } ?: names.getOrElse(0) { "image" }
        val maskInputName = names.firstOrNull { it.contains("mask", ignoreCase = true) } ?: names.getOrElse(1) { "mask" }

        val outBytes = ByteArray(3 * plane)
        OnnxTensor.createTensor(ortEnv, imgBuf, longArrayOf(1, 3, inferH.toLong(), inferW.toLong()), OnnxJavaType.UINT8).use { imgTensor ->
            OnnxTensor.createTensor(ortEnv, maskBuf, longArrayOf(1, 1, inferH.toLong(), inferW.toLong()), OnnxJavaType.UINT8).use { mTensor ->
                session.run(mapOf(imgInputName to imgTensor, maskInputName to mTensor)).use { res ->
                    val outTensor = res[0] as OnnxTensor
                    val outBb = outTensor.byteBuffer
                    outBb.rewind()
                    outBb.get(outBytes, 0, min(outBb.remaining(), outBytes.size))
                }
            }
        }

        val inferOutPx = IntArray(plane)
        for (i in 0 until plane) {
            val r = outBytes[i].toInt() and 0xFF
            val g = outBytes[plane + i].toInt() and 0xFF
            val b = outBytes[plane * 2 + i].toInt() and 0xFF
            inferOutPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val inpaintedInferBmp = Bitmap.createBitmap(inferW, inferH, Bitmap.Config.ARGB_8888).apply {
            setPixels(inferOutPx, 0, inferW, 0, 0, inferW, inferH)
        }
        val inpaintedCropBmp = if (inferScale < 1f) {
            Bitmap.createScaledBitmap(inpaintedInferBmp, cropW, cropH, true).also {
                if (it !== inpaintedInferBmp) inpaintedInferBmp.recycle()
            }
        } else inpaintedInferBmp

        if (inferColor !== cropColor) inferColor.recycle()
        if (inferStroke !== cropMaskBmp) inferStroke.recycle()

        // สร้าง Feather Alpha ขอบนุ่มเพื่อให้รอยต่อกลืนไปกับภาพต้นฉบับความละเอียดเต็ม 100%
        val blendMaskSmall = Bitmap.createBitmap(inferW, inferH, Bitmap.Config.ARGB_8888)
        val blendSmallPx = IntArray(plane)
        for (i in 0 until plane) {
            blendSmallPx[i] = if (holeMask[i]) (0xFF shl 24) else 0
        }
        blendMaskSmall.setPixels(blendSmallPx, 0, inferW, 0, 0, inferW, inferH)
        val blendMaskCrop = Bitmap.createScaledBitmap(blendMaskSmall, cropW, cropH, true)
        if (blendMaskCrop !== blendMaskSmall) blendMaskSmall.recycle()

        val origCropPx = IntArray(cropW * cropH)
        val newCropPx = IntArray(cropW * cropH)
        val blendPx = IntArray(cropW * cropH)
        cropColor.getPixels(origCropPx, 0, cropW, 0, 0, cropW, cropH)
        inpaintedCropBmp.getPixels(newCropPx, 0, cropW, 0, 0, cropW, cropH)
        blendMaskCrop.getPixels(blendPx, 0, cropW, 0, 0, cropW, cropH)

        for (i in origCropPx.indices) {
            val a = (blendPx[i] ushr 24) and 0xFF
            if (a > 0) {
                val orig = origCropPx[i]
                val rep = newCropPx[i]
                val r = (((rep shr 16) and 0xFF) * a + ((orig shr 16) and 0xFF) * (255 - a)) / 255
                val g = (((rep shr 8) and 0xFF) * a + ((orig shr 8) and 0xFF) * (255 - a)) / 255
                val b = ((rep and 0xFF) * a + (orig and 0xFF) * (255 - a)) / 255
                origCropPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        cropColor.recycle()
        cropMaskBmp.recycle()
        inpaintedCropBmp.recycle()
        blendMaskCrop.recycle()

        val finalBmp = colorSrc.copy(Bitmap.Config.ARGB_8888, true)
        finalBmp.setPixels(origCropPx, 0, cropW, cropLeft, cropTop, cropW, cropH)
        finalBmp
    }
}
