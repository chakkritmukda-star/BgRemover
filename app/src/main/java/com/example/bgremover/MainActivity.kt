package com.example.bgremover

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    data class PhotoItem(
        val original: Bitmap,
        var result: Bitmap? = null,
        var hasBgRemoved: Boolean = false,
        var isProcessing: Boolean = false,
        var elapsedMs: Long = 0L
    )

    private val photoList = mutableListOf<PhotoItem>()
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var btnRemove: Button
    private lateinit var btnSaveAll: Button
    private lateinit var btnFastMode: Button
    private lateinit var btnHdMode: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private var editingIndex: Int = -1
    private var selectedQuality: AiEngine.BgQuality = AiEngine.BgQuality.FAST

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && editingIndex in photoList.indices) {
            BitmapStore.editedResult?.let { edited ->
                val item = photoList[editingIndex]
                item.result = edited
                item.hasBgRemoved = BitmapStore.hasBgRemoved
                if (!BitmapStore.hasBgRemoved) {
                    BitmapStore.editingOriginal?.let { updatedOrig ->
                        photoList[editingIndex] = item.copy(original = updatedOrig, result = edited)
                    }
                }
                photoAdapter.notifyItemChanged(editingIndex)
                btnSaveAll.isEnabled = photoList.any { it.result != null }
                tvStatus.text = "✨ อัปเดตรูปที่ ${editingIndex + 1} เรียบร้อย!"
            }
        }
        BitmapStore.clearSession()
    }

    private val pickMultipleImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                tvStatus.text = "กำลังเตรียมรูปภาพ ${uris.size} รูป..."
                progressBar.visibility = View.VISIBLE
                val startIdx = photoList.size

                // โหลดโมเดลไปพร้อมกับการถอดรหัสรูป ไม่ต้องรอกัน
                launch { AiEngine.warmUp(this@MainActivity, selectedQuality) }

                // ถอดรหัสรูปแบบขนานทีละ 3 รูป (กัน RAM พุ่ง)
                val loaded = withContext(Dispatchers.IO) {
                    uris.chunked(3).flatMap { chunk ->
                        chunk.map { uri ->
                            async {
                                runCatching {
                                    AiEngine.decodeSmartBitmap(this@MainActivity, uri, maxEdge = 1600)
                                        ?.let { PhotoItem(original = it) }
                                }.getOrNull()
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
                photoList.addAll(loaded)
                photoAdapter.notifyItemRangeInserted(startIdx, loaded.size)
                progressBar.visibility = View.GONE
                btnRemove.isEnabled = photoList.isNotEmpty()
                btnSaveAll.isEnabled = photoList.any { it.result != null }
                tvStatus.text = "เลือกรูปแล้ว ${photoList.size} รูป (แตะที่รูปเพื่อใช้ ✨ Magic Eraser ได้ทันที)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val title = TextView(this).apply {
            text = "✂️ BgRemover & Magic Eraser"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 12)
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 14)
        }

        btnFastMode = Button(this).apply {
            text = "⚡ เร็วด่วน (~0.4s)"
            textSize = 12.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { updateQualityMode(AiEngine.BgQuality.FAST, warmUpFile = true) }
        }

        btnHdMode = Button(this).apply {
            text = "💎 คมชัด HD (เก็บเส้นผม)"
            textSize = 12.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener { updateQualityMode(AiEngine.BgQuality.HD, warmUpFile = true) }
        }
        modeRow.addView(btnFastMode)
        modeRow.addView(btnHdMode)

        photoAdapter = PhotoAdapter()
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = photoAdapter
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(12, 12, 12, 12)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            visibility = View.GONE
            setPadding(0, 14, 0, 4)
        }

        tvStatus = TextView(this).apply {
            text = "กด 'เพิ่มรูปภาพ' เพื่อตัดพื้นหลังไว หรือแตะรูปเพื่อใช้ Magic Eraser"
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            textSize = 13f
            setPadding(0, 8, 0, 14)
        }

        val topBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnPick = makeRoundedButton("📁 เพิ่มรูปภาพ", "#334155").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { pickMultipleImages.launch("image/*") }
        }

        val btnClear = makeRoundedButton("🗑️ ล้างรายการ", "#475569").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener {
                photoList.clear()
                photoAdapter.notifyDataSetChanged()
                btnRemove.isEnabled = false
                btnSaveAll.isEnabled = false
                tvStatus.text = "ล้างรายการเรียบร้อย"
            }
        }
        topBtnRow.addView(btnPick)
        topBtnRow.addView(btnClear)

        val actionBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }

        btnRemove = makeRoundedButton("⚡ ลบพื้นหลังทั้งหมด", "#7C3AED").apply {
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { processBatchRemoveBg() }
        }

        btnSaveAll = makeRoundedButton("💾 บันทึกทั้งหมด", "#10B981").apply {
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener { saveAllImages() }
        }
        actionBtnRow.addView(btnRemove)
        actionBtnRow.addView(btnSaveAll)

        root.addView(title)
        root.addView(modeRow)
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(progressBar)
        root.addView(tvStatus)
        root.addView(topBtnRow)
        root.addView(actionBtnRow)

        setContentView(root)
        updateQualityMode(AiEngine.BgQuality.FAST, warmUpFile = false)
    }

    private fun makeRoundedButton(label: String, hexColor: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 24f
            }
        }
    }

    private fun updateQualityMode(quality: AiEngine.BgQuality, warmUpFile: Boolean) {
        selectedQuality = quality
        val activeBg = GradientDrawable().apply {
            setColor(Color.parseColor("#7C3AED"))
            cornerRadius = 22f
        }
        val inactiveBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            setStroke(2, Color.parseColor("#475569"))
            cornerRadius = 22f
        }
        btnFastMode.background = if (quality == AiEngine.BgQuality.FAST) activeBg else inactiveBg
        btnHdMode.background = if (quality == AiEngine.BgQuality.HD) activeBg else inactiveBg
        btnFastMode.setTextColor(Color.WHITE)
        btnHdMode.setTextColor(Color.WHITE)

        if (warmUpFile) {
            lifecycleScope.launch {
                AiEngine.warmUp(this@MainActivity, quality)
            }
        }
    }

    private fun openEditorForIndex(index: Int, startInMagicEraser: Boolean) {
        if (index !in photoList.indices) return
        val item = photoList[index]
        if (item.isProcessing) return

        editingIndex = index
        BitmapStore.editingOriginal = item.original
        BitmapStore.editingCurrent = item.result ?: item.original
        BitmapStore.hasBgRemoved = item.hasBgRemoved

        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra("START_MAGIC", startInMagicEraser || !item.hasBgRemoved)
        }
        editLauncher.launch(intent)
    }

    private fun processBatchRemoveBg() {
        btnRemove.isEnabled = false
        btnSaveAll.isEnabled = false
        progressBar.max = photoList.size
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val batchStart = SystemClock.elapsedRealtime()
            for (i in photoList.indices) {
                val item = photoList[i]
                item.isProcessing = true
                photoAdapter.notifyItemChanged(i)
                tvStatus.text = "กำลังตัดพื้นหลังรูปที่ ${i + 1}/${photoList.size}..."

                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val baseInput = if (!item.hasBgRemoved && item.result != null) item.result!! else item.original
                    val cut = AiEngine.removeBackground(this@MainActivity, baseInput, selectedQuality)
                    item.result = cut
                    item.hasBgRemoved = true
                    item.elapsedMs = SystemClock.elapsedRealtime() - t0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    showErrorDialog("ตัดพื้นหลังรูปที่ ${i + 1} ไม่สำเร็จ", t)
                } finally {
                    item.isProcessing = false
                    progressBar.progress = i + 1
                    photoAdapter.notifyItemChanged(i)
                }
            }
            val totalSec = (SystemClock.elapsedRealtime() - batchStart) / 1000f
            progressBar.visibility = View.GONE
            btnRemove.isEnabled = true
            btnSaveAll.isEnabled = photoList.any { it.result != null }
            tvStatus.text = "✅ เสร็จครบ ${photoList.size} รูป ใน ${"%.2f".format(totalSec)} วินาที! (แตะรูปเพื่อใช้ Magic Eraser ต่อได้)"
        }
    }

    private fun showErrorDialog(titleText: String, t: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage(t.localizedMessage ?: t.toString())
            .setPositiveButton("ตกลง", null)
            .show()
    }

    private fun saveAllImages() {
        lifecycleScope.launch {
            tvStatus.text = "กำลังบันทึกภาพลงอัลบั้ม..."
            var savedCount = 0
            withContext(Dispatchers.IO) {
                for (item in photoList) {
                    val bmp = item.result ?: continue
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "BgRemover_${System.currentTimeMillis()}_$savedCount.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BgRemover")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        savedCount++
                    }
                }
            }
            tvStatus.text = "💾 บันทึกลงอัลบั้ม Pictures/BgRemover แล้ว $savedCount รูป!"
            Toast.makeText(this@MainActivity, "บันทึกสำเร็จ $savedCount รูป!", Toast.LENGTH_LONG).show()
        }
    }

    inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.VH>() {

        inner class VH(
            val card: FrameLayout,
            val img: ImageView,
            val spin: ProgressBar,
            val badge: TextView,
            val magicChip: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460).apply {
                    setMargins(10, 10, 10, 10)
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#334155"))
                    cornerRadius = 24f
                }
                clipToOutline = true
            }
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8, 8, 8, 36)
            }
            val spin = ProgressBar(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(96, 96, Gravity.CENTER)
                visibility = View.GONE
            }
            val magicChip = TextView(parent.context).apply {
                text = "✨ Magic Eraser"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(18, 8, 18, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC7C3AED"))
                    cornerRadius = 30f
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply { setMargins(12, 12, 12, 12) }
            }
            val badge = TextView(parent.context).apply {
                textSize = 11.5f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC0F172A"))
                gravity = Gravity.CENTER
                setPadding(8, 10, 8, 10)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }
            card.addView(img, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            card.addView(spin)
            card.addView(magicChip)
            card.addView(badge)
            return VH(card, img, spin, badge, magicChip)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = photoList[position]
            val display = item.result ?: item.original
            holder.img.setImageBitmap(if (item.hasBgRemoved) drawCheckerPreview(display) else display)
            holder.spin.visibility = if (item.isProcessing) View.VISIBLE else View.GONE
            holder.magicChip.visibility = if (item.isProcessing) View.GONE else View.VISIBLE

            holder.badge.text = when {
                item.isProcessing -> "⏳ กำลังประมวลผล..."
                item.hasBgRemoved -> "⚡ ${"%.2f".format(item.elapsedMs / 1000f)}s • แตะเพื่อแต่งขอบ / Magic Eraser"
                item.result != null -> "✨ ลบวัตถุแล้ว • แตะเพื่อแก้เพิ่ม"
                else -> "แตะเพื่อลบวัตถุ (Magic Eraser)"
            }

            holder.card.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    openEditorForIndex(pos, startInMagicEraser = !item.hasBgRemoved)
                }
            }
            holder.magicChip.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    openEditorForIndex(pos, startInMagicEraser = true)
                }
            }
        }

        override fun getItemCount() = photoList.size
    }

    private fun drawCheckerPreview(src: Bitmap): Bitmap {
        val maxSide = 512
        val scale = if (src.width > maxSide || src.height > maxSide) {
            maxSide.toFloat() / maxOf(src.width, src.height)
        } else 1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val p1 = Paint().apply { color = Color.parseColor("#CBD5E1") }
        val p2 = Paint().apply { color = Color.parseColor("#F8FAFC") }
        val step = 20
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val paint = if (((x / step) + (y / step)) % 2 == 0) p1 else p2
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), (y + step).toFloat(), paint)
            }
        }
        val destRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(src, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
// END OF MainActivity.kt
