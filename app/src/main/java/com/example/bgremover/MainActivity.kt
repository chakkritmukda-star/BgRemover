package com.example.bgremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.nio.FloatBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout
    private lateinit var cbWhite: CheckBox
    private val results = mutableListOf<Bitmap>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val SIZE = 1024
    private val MAX_OUT = 2048
    private var busy = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            if (uris.isNotEmpty() && !busy) processImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        container = findViewById(R.id.container)
        cbWhite = findViewById(R.id.cbWhite)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            if (busy) {
                Toast.makeText(this, "กำลังทำงานอยู่ รอสักครู่", Toast.LENGTH_SHORT).show()
            } else {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
    }

    private suspend fun getSession(): OrtSession = withContext(Dispatchers.IO) {
        session ?: run {
            val file = File(filesDir, "model.onnx")
            if (!file.exists() || file.length() == 0L) {
                val tmp = File(filesDir, "model.tmp")
                assets.open("model.onnx").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(file)
            }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            env.createSession(file.absolutePath, opts).also { session = it }
        }
    }

    private fun processImages(uris: List<Uri>) = lifecycleScope.launch {
        busy = true
        results.clear()
        container.removeAllViews()
        val white = cbWhite.isChecked

        tvStatus.text = "กำลังเตรียม AI (ครั้งแรกอาจใช้เวลา 10-30 วินาที)..."
        val s = try {
            getSession()
        } catch (e: Exception) {
            tvStatus.text = "โหลด AI ไม่สำเร็จ: ${e.message}"
            busy = false
            return@launch
        }

        for ((i, uri) in uris.withIndex()) {
            tvStatus.text = "กำลังลบพื้นหลัง ${i + 1}/${uris.size} ..."
            try {
                val out = withContext(Dispatchers.Default) {
                    val src = loadBitmap(uri)
                    val cut = removeBg(s, src)
                    if (white) addWhiteBackground(cut) else cut
                }
                results.add(out)
                addPreview(out)
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "รูปที่ ${i + 1} ผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        tvStatus.text = "เสร็จแล้ว ${results.size}/${uris.size} รูป กดบันทึกได้เลย"
        busy = false
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, MAX_OUT.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun removeBg(s: OrtSession, src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val area = SIZE * SIZE

        // เตรียมภาพเข้า AI (1024x1024)
        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(area)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val buf = FloatBuffer.allocate(3 * area)
        val arr = buf.array()
        for (k in 0 until area) {
            val c = px[k]
            arr[k] = ((c shr 16) and 0xFF) / 255f - 0.5f
            arr[area + k] = ((c shr 8) and 0xFF) / 255f - 0.5f
            arr[2 * area + k] = (c and 0xFF) / 255f - 0.5f
        }

        // ให้ AI สร้าง mask
        val inputName = s.inputNames.iterator().next()
        val mask: FloatArray = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        ).use { t ->
            s.run(mapOf(inputName to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }

        // ปรับค่า mask เป็น 0-255
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in mask) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        val maskPx = IntArray(area)
        for (k in 0 until area) {
            val a = (((mask[k] - mn) / range) * 255f).toInt().coerceIn(0, 255)
            maskPx[k] = a shl 24
        }
        val maskSmall = Bitmap.createBitmap(maskPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val maskFull = Bitmap.createScaledBitmap(maskSmall, w, h, true)

        // ตัดพื้นหลังออกตาม mask
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskFull, 0f, 0f, p)
        return out
    }

    private fun addWhiteBackground(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun addPreview(bmp: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }
        container.addView(iv)
    }

    private fun saveAll() {
        if (results.isEmpty()) {
            Toast.makeText(this, "ยังไม่มีรูปให้บันทึก", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val time = System.currentTimeMillis()
            results.forEachIndexed { i, bmp -> saveToGallery(bmp, "nobg_${time}_$i.png") }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "บันทึก ${results.size} รูปแล้ว (Pictures/BgRemover)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BgRemover")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
