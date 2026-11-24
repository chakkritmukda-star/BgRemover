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
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var container: LinearLayout
    private lateinit var cbWhite: CheckBox
    private val results = mutableListOf<Bitmap>()
    private val client = OkHttpClient()

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
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
            tvStatus.text = "กำลังลบพื้นหลังด้วย AI ขั้นสูง ${i + 1}/${uris.size} ..."
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: continue

                val processedBytes = withContext(Dispatchers.IO) { removeBackgroundWithAI(bytes) }
                val bmp = BitmapFactory.decodeByteArray(processedBytes, 0, processedBytes.size) ?: continue
                
                var finalBmp = bmp
                if (cbWhite.isChecked) finalBmp = addWhiteBackground(bmp)

                results.add(finalBmp)
                addPreview(finalBmp)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "รูปที่ ${i + 1} ผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        tvStatus.text = "เสร็จแล้ว ${results.size}/${uris.size} รูป (เนียนกริบพร้อมขายของ)"
    }

    private fun removeBackgroundWithAI(imageBytes: ByteArray): ByteArray {
        val requestBody = imageBytes.toRequestBody("image/*".toMediaType())
        // ใช้ public API ฟรีสำหรับลบพื้นหลังความละเอียดสูง
        val request = Request.Builder()
            .url("https://bite-remove-bg.p.rapidapi.com/remove-bg") // หรือใช้ Endpoint สาธารณะฟรี
            .post(requestBody)
            .build()
        
        // หากต้องการแบบฟรี 100% ไม่ต้องผ่านบริการจำกัดโควตา ใช้การประมวลผลผ่าน Webhook หรือ Public HF Space ได้
        // ในที่นี้ปรับใช้ Endpoint มาตรฐานที่รองรับภาพสินค้า
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("AI Error: ${response.code}")
            return response.body?.bytes() ?: throw Error("Empty response")
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
            results.forEachIndexed { i, bmp -> saveToGallery(bmp, "pro_bg_${time}_$i.png") }
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
