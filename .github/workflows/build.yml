package com.example.bgremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentValues
import android.content.Intent
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.nio.FloatBuffer

class Item(val src: File, val aiMask: File, val mask: File, var thumb: Bitmap)

object Store {
    val items = mutableListOf<Item>()
}

object Img {
    private var tile: Bitmap? = null

    fun decode(f: File): Bitmap {
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, o) ?: throw Exception("อ่านไฟล์ไม่ได้")
    }

    fun savePng(b: Bitmap, f: File) {
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun composeCut(src: Bitmap, mask: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        c.drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        return out
    }

    fun addWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    fun makeThumb(src: Bitmap, mask: Bitmap): Bitmap {
        val cut = composeCut(src, mask)
        val sc = minOf(1f, 600f / maxOf(cut.width, cut.height))
        val t = Bitmap.createScaledBitmap(
            cut,
            (cut.width * sc).toInt().coerceAtLeast(1),
            (cut.height * sc).toInt().coerceAtLeast(1),
            true
        )
        if (t !== cut) cut.recycle()
        return t
    }

    fun checkerTile(): Bitmap {
        tile?.let { return it }
        val s = 24
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.rgb(215, 215, 215) }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        tile = b
        return b
    }

    fun checker(res: Resources): Drawable =
        BitmapDrawable(res, checkerTile()).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout
    private lateinit var cbWhite: CheckBox
    private val views = mutableListOf<ImageView>()
    private var currentRow: LinearLayout? = null

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val SIZE = 1024
    private val MAX_OUT = 1600
    private var busy = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            if (uris.isNotEmpty() && !busy) processImages(uris)
        }

    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val idx = res.data?.getIntExtra("index", -1) ?: -1
            if (res.resultCode == RESULT_OK && idx in views.indices && idx in Store.items.indices) {
                views[idx].setImageBitmap(Store.items[idx].thumb)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        container = findViewById(R.id.container)
        cbWhite = findViewById(R.id.cbWhite)

        if (Store.items.isEmpty()) File(cacheDir, "work").deleteRecursively()

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            if (busy) toast("กำลังทำงานอยู่ รอสักครู่")
            else pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
        cbWhite.setOnCheckedChangeListener { _, _ -> views.forEach { it.background = bg() } }

        rebuildPreviews()
        if (Store.items.isNotEmpty()) showReadyText()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showReadyText() {
        tvStatus.text = "มี ${Store.items.size} รูป • แตะรูปเพื่อแก้ด้วยพู่กัน AI • กดค้างเพื่อเอาออก"
    }

    private fun bg(): Drawable =
        if (cbWhite.isChecked) ColorDrawable(Color.WHITE) else Img.checker(resources)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rebuildPreviews() {
        container.removeAllViews()
        views.clear()
        currentRow = null
        Store.items.indices.forEach { addPreview(it) }
    }

    private fun addPreview(idx: Int) {
        if (idx % 2 == 0 || currentRow == null) {
            currentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(currentRow)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(Store.items[idx].thumb)
            background = bg()
            layoutParams = LinearLayout.LayoutParams(0, dp(180), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnClickListener { openEditor(idx) }
            setOnLongClickListener {
                if (!busy) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("เอารูปนี้ออกจากรายการ?")
                        .setPositiveButton("เอาออก") { _, _ -> removeItem(idx) }
                        .setNegativeButton("ยกเลิก", null)
                        .show()
                }
                true
            }
        }
        currentRow!!.addView(iv)
        views.add(iv)
    }

    private fun removeItem(idx: Int) {
        if (idx !in Store.items.indices) return
        val it = Store.items.removeAt(idx)
        it.src.delete(); it.aiMask.delete(); it.mask.delete()
        rebuildPreviews()
        showReadyText()
    }

    private fun openEditor(idx: Int) {
        if (busy) {
            toast("รอให้ทำงานเสร็จก่อนนะ")
            return
        }
        editLauncher.launch(
            Intent(this, EditorActivity::class.java)
                .putExtra("index", idx)
                .putExtra("white", cbWhite.isChecked)
        )
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
        Store.items.forEach { it.src.delete(); it.aiMask.delete(); it.mask.delete() }
        Store.items.clear()
        rebuildPreviews()

        tvStatus.text = "กำลังโหลด BiRefNet AI (ครั้งแรกอาจใช้เวลา 10-30 วินาที)..."
        val s = try {
            getSession()
        } catch (e: Throwable) {
            tvStatus.text = "โหลด AI ไม่สำเร็จ: ${e.message}"
            busy = false
            return@launch
        }

        val dir = File(cacheDir, "work").apply { mkdirs() }
        for ((i, uri) in uris.withIndex()) {
            tvStatus.text = "กำลังลบพื้นหลังด้วย AI ขั้นสูง ${i + 1}/${uris.size} ..."
            try {
                val item = withContext(Dispatchers.Default) {
                    val src = loadBitmap(uri)
                    val mask = makeMask(s, src)
                    val id = "${System.nanoTime()}_$i"
                    val srcF = File(dir, "src_$id.jpg")
                    val aiF = File(dir, "ai_$id.png")
                    val maskF = File(dir, "mask_$id.png")
                    srcF.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    Img.savePng(mask, aiF)
                    aiF.copyTo(maskF, overwrite = true)
                    val thumb = Img.makeThumb(src, mask)
                    src.recycle()
                    mask.recycle()
                    Item(srcF, aiF, maskF, thumb)
                }
                Store.items.add(item)
                addPreview(Store.items.size - 1)
            } catch (e: Throwable) {
                toast("รูปที่ ${i + 1} ผิดพลาด: ${e.message}")
            }
        }
        busy = false
        showReadyText()
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        val b = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, MAX_OUT.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, false)
        b.recycle()
        return c
    }

    private fun makeMask(s: OrtSession, src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val area = SIZE * SIZE

        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(area)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== src) scaled.recycle()

        val buf = FloatBuffer.allocate(3 * area)
        val arr = buf.array()
        for (k in 0 until area) {
            val c = px[k]
            arr[k] = ((c shr 16) and 0xFF) / 255f
            arr[area + k] = ((c shr 8) and 0xFF) / 255f
            arr[2 * area + k] = (c and 0xFF) / 255f
        }

        val inputName = s.inputNames.iterator().next()
        val out: FloatArray = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        ).use { t ->
            s.run(mapOf(inputName to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }

        val maskPx = IntArray(area)
        for (k in 0 until area) {
            val a = out[k].coerceIn(0f, 1f)
            maskPx[k] = (a * 255f).toInt() shl 24
        }
        val small = Bitmap.createBitmap(maskPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val full = Bitmap.createScaledBitmap(small, w, h, true)
        if (full !== small) small.recycle()
        return full
    }

    private fun saveAll() {
        if (busy) { toast("รอให้ทำงานเสร็จก่อนนะ"); return }
        if (Store.items.isEmpty()) { toast("ยังไม่มีรูปให้บันทึก"); return }
        busy = true
        val white = cbWhite.isChecked
        lifecycleScope.launch {
            val list = Store.items.toList()
            val time = System.currentTimeMillis()
            var ok = 0
            for ((i, item) in list.withIndex()) {
                tvStatus.text = "กำลังบันทึก ${i + 1}/${list.size} ..."
                try {
                    withContext(Dispatchers.IO) {
                        val src = Img.decode(item.src)
                        val mask = Img.decode(item.mask)
                        var out = Img.composeCut(src, mask)
                        src.recycle()
                        mask.recycle()
                        if (white) {
                            val w = Img.addWhite(out)
                            out.recycle()
                            out = w
                        }
                        saveToGallery(out, "nobg_${time}_${i + 1}.png")
                        out.recycle()
                    }
                    ok++
                } catch (e: Throwable) {
                    toast("บันทึกรูปที่ ${i + 1} ไม่สำเร็จ")
                }
            }
            busy = false
            tvStatus.text = "บันทึกแล้ว $ok รูป (Pictures/BgRemover)"
            Toast.makeText(this@MainActivity, "บันทึก $ok รูปแล้ว", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BgRemover")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("สร้างไฟล์ไม่ได้")
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
