package com.example.bgremover

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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout
    private lateinit var cbWhite: CheckBox
    private val results = mutableListOf<Bitmap>()
    private val MAX_SIZE = 2048

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isNotEmpty()) processImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        container = findViewById(R.id.container)
        cbWhite = findViewById(R.id.cbWhite)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
    }

    private fun processImages(uris: List<Uri>) = lifecycleScope.launch {
        results.clear()
        container.removeAllViews()
        for ((i, uri) in uris.withIndex()) {
            tvStatus.text = "กำลังลบพื้นหลัง ${i + 1}/${uris.size} ..."
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(uri) }
                val result = segmenter.process(InputImage.fromBitmap(bmp, 0)).await()
                var fg = result.foregroundBitmap ?: continue
                if (cbWhite.isChecked) fg = addWhiteBackground(fg)
                results.add(fg)
                addPreview(fg)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "รูปที่ ${i + 1} ผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        tvStatus.text = "เสร็จแล้ว ${results.size}/${uris.size} รูป"
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
        return android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(w, h))
            decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
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
