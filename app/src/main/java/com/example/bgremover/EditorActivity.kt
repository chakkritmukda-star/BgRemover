package com.example.bgremover

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity() {

    private lateinit var editView: MaskEditView
    private lateinit var tvHint: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var tvLoadingMsg: TextView

    private lateinit var btnUndo: Button
    private lateinit var btnRedo: Button
    private lateinit var btnMagic: Button
    private lateinit var btnEraseManual: Button
    private lateinit var btnRestoreManual: Button
    private lateinit var btnSmartCutout: Button
    private lateinit var btnPan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val origBmp = BitmapStore.editingOriginal
        val currBmp = BitmapStore.editingCurrent
        val hasBgRemoved = BitmapStore.hasBgRemoved

        if (origBmp == null || currBmp == null) {
            Toast.makeText(this, "ไม่พบรูปภาพสำหรับแก้ไข", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            AiEngine.warmUp(this@EditorActivity)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        // แถบบน: ยกเลิก | ย้อนกลับ/ทำซ้ำ | เทียบต้นฉบับ | เสร็จสิ้น
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 24, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val btnCancel = smallActionButton("✖ ออก", "#334155") { finish() }

        btnUndo = smallActionButton("↩", "#334155") { editView.undo() }.apply { isEnabled = false }
        btnRedo = smallActionButton("↪", "#334155") { editView.redo() }.apply { isEnabled = false }

        val btnCompare = smallActionButton("👁 เทียบ", "#475569") {}.apply {
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> editView.setCompareOriginal(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> editView.setCompareOriginal(false)
                }
                true
            }
        }

        val btnDone = smallActionButton("✔ บันทึก", "#10B981") {
            BitmapStore.editedResult = editView.exportFinalBitmap()
            BitmapStore.hasBgRemoved = editView.hasTransparency()
            BitmapStore.editingOriginal = editView.getCurrentColorBitmap()
            setResult(Activity.RESULT_OK)
            finish()
        }

        topBar.addView(btnCancel)
        topBar.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(btnUndo)
        topBar.addView(Space(this), LinearLayout.LayoutParams(10, 1))
        topBar.addView(btnRedo)
        topBar.addView(Space(this), LinearLayout.LayoutParams(10, 1))
        topBar.addView(btnCompare)
        topBar.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(btnDone)

        tvHint = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#1E1B4B"))
            gravity = Gravity.CENTER
            textSize = 12.5f
            setPadding(20, 14, 20, 14)
        }

        // พื้นที่แคนวาสพร้อม Loading Overlay ตอนรัน AI Inpainting
        val canvasContainer = FrameLayout(this)
        editView = MaskEditView(this).apply {
            setImages(origBmp, currBmp, hasBgRemoved)
            onHistoryChanged = { canU, canR ->
                btnUndo.isEnabled = canU
                btnRedo.isEnabled = canR
                btnUndo.alpha = if (canU) 1f else 0.4f
                btnRedo.alpha = if (canR) 1f else 0.4f
            }
            onMagicEraseStrokeFinished = { strokeMask ->
                runMagicEraserInpaint(strokeMask)
            }
        }

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#880F172A"))
            visibility = View.GONE
            val box = LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 36, 48, 36)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 32f
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            val spin = ProgressBar(this@EditorActivity)
            tvLoadingMsg = TextView(this@EditorActivity).apply {
                text = "✨ AI กำลังลบวัตถุให้เนียน..."
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 18, 0, 0)
            }
            box.addView(spin)
            box.addView(tvLoadingMsg)
            addView(box)
        }

        canvasContainer.addView(editView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        canvasContainer.addView(loadingOverlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // แถบควบคุมด้านล่าง
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 18, 24, 28)
        }

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        val tvSizeLabel = TextView(this).apply {
            text = "ขนาดแปรง: 44"
            setTextColor(Color.WHITE)
            textSize = 12.5f
        }
        val seekBrush = SeekBar(this).apply {
            max = 130
            progress = 44
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val radius = p.coerceAtLeast(10)
                    editView.brushRadiusScreen = radius.toFloat()
                    tvSizeLabel.text = "ขนาดแปรง: $radius"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val btnQuickCutBg = smallActionButton("⚡ ตัดพื้นหลัง AI", "#7C3AED") {
            runQuickRemoveBgInEditor()
        }

        sizeRow.addView(tvSizeLabel)
        sizeRow.addView(seekBrush, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(btnQuickCutBg)

        val modeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnMagic = toolChip("✨ Magic Eraser") { selectMode(MaskEditView.Mode.MAGIC_ERASER) }
        btnEraseManual = toolChip("🧹 ลบพื้นหลัง") { selectMode(MaskEditView.Mode.ERASE_MANUAL) }
        btnRestoreManual = toolChip("🖌️ กู้คืน") { selectMode(MaskEditView.Mode.RESTORE_MANUAL) }
        btnSmartCutout = toolChip("🪄 ลบสีใกล้เคียง") { selectMode(MaskEditView.Mode.SMART_CUTOUT) }
        btnPan = toolChip("🖐 เลื่อน/ซูม") { selectMode(MaskEditView.Mode.PAN) }

        listOf(btnMagic, btnEraseManual, btnRestoreManual, btnSmartCutout, btnPan).forEach { chip ->
            modeRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 })
        }
        modeScroll.addView(modeRow)

        bottomPanel.addView(sizeRow)
        bottomPanel.addView(modeScroll)

        root.addView(topBar)
        root.addView(tvHint)
        root.addView(canvasContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomPanel)

        setContentView(root)

        val startMagic = intent.getBooleanExtra("START_MAGIC", true)
        selectMode(if (startMagic) MaskEditView.Mode.MAGIC_ERASER else MaskEditView.Mode.ERASE_MANUAL)
    }

    private fun runMagicEraserInpaint(strokeMask: android.graphics.Bitmap) {
        val currentColor = editView.getCurrentColorBitmap() ?: run {
            strokeMask.recycle()
            return
        }
        editView.setProcessingBusy(true)
        tvLoadingMsg.text = "✨ Magic Eraser กำลังลบวัตถุให้เนียน..."
        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val inpainted = AiEngine.magicEraseInpaint(this@EditorActivity, currentColor, strokeMask)
                editView.applyInpaintedBitmap(inpainted)
            } catch (e: Exception) {
                Toast.makeText(this@EditorActivity, "Magic Eraser ผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                currentColor.recycle()
                strokeMask.recycle()
                loadingOverlay.visibility = View.GONE
                editView.setProcessingBusy(false)
            }
        }
    }

    private fun runQuickRemoveBgInEditor() {
        val currentColor = editView.getCurrentColorBitmap() ?: return
        editView.setProcessingBusy(true)
        tvLoadingMsg.text = "⚡ AI กำลังตัดพื้นหลัง..."
        loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val cutout = AiEngine.removeBackground(this@EditorActivity, currentColor, AiEngine.BgQuality.FAST)
                editView.setImages(currentColor, cutout, hasBgRemoved = true)
            } catch (e: Exception) {
                Toast.makeText(this@EditorActivity, "ตัดพื้นหลังผิดพลาด: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                currentColor.recycle()
                loadingOverlay.visibility = View.GONE
                editView.setProcessingBusy(false)
            }
        }
    }

    private fun smallActionButton(textLabel: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textLabel
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(26, 12, 26, 12)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = 20f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toolChip(textLabel: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textLabel
            textSize = 12.5f
            isAllCaps = false
            setPadding(32, 16, 32, 16)
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
    }

    private fun selectMode(m: MaskEditView.Mode) {
        editView.mode = m
        val all = mapOf(
            MaskEditView.Mode.MAGIC_ERASER to btnMagic,
            MaskEditView.Mode.ERASE_MANUAL to btnEraseManual,
            MaskEditView.Mode.RESTORE_MANUAL to btnRestoreManual,
            MaskEditView.Mode.SMART_CUTOUT to btnSmartCutout,
            MaskEditView.Mode.PAN to btnPan
        )
        for ((modeKey, button) in all) {
            val active = modeKey == m
            button.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (active) "#7C3AED" else "#334155"))
                cornerRadius = 24f
            }
        }
        tvHint.text = when (m) {
            MaskEditView.Mode.MAGIC_ERASER -> "✨ Magic Eraser: ระบายทับคน/สิ่งของ/ตัวหนังสือ แล้วปล่อยนิ้ว AI จะลบและเติมพื้นหลังให้เนียนอัตโนมัติ (ใช้ 2 นิ้วซูมได้)"
            MaskEditView.Mode.ERASE_MANUAL -> "🧹 ลบพื้นหลัง: ใช้นิ้วระบายส่วนที่ต้องการเจาะให้โปร่งใส"
            MaskEditView.Mode.RESTORE_MANUAL -> "🖌️ กู้คืน: ระบายดึงส่วนของภาพที่แหว่งให้กลับคืนมา"
            MaskEditView.Mode.SMART_CUTOUT -> "🪄 ลบสีใกล้เคียง: ระบายแตะสีพื้นหลังที่ติดค้าง ระบบจะเจาะเฉพาะโทนสีนั้นให้ใส"
            MaskEditView.Mode.PAN -> "🖐 เลื่อน/ซูม: ใช้ 1 นิ้วลากเลื่อนภาพ และถ่าง 2 นิ้วเพื่อซูมเข้า-ออก"
        }
    }
}
