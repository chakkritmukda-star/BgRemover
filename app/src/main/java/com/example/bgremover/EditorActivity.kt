package com.example.bgremover

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class EditorActivity : AppCompatActivity() {

    private lateinit var editView: MaskEditView
    private lateinit var item: Item
    private lateinit var btnEraseMan: Button
    private lateinit var btnEraseMagic: Button
    private lateinit var btnRestMan: Button
    private lateinit var btnRestMagic: Button
    private lateinit var hint: TextView
    private var index = -1
    private var edited = false
    private var saving = false

    private val ACCENT = Color.rgb(124, 77, 255)
    private val GRAY = Color.rgb(66, 66, 66)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra("index", -1)
        val found = Store.items.getOrNull(index)
        if (found == null) { finish(); return }
        item = found

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 28, 28))
        }

        val top = row()
        top.addView(btn("ยกเลิก") { askDiscard() })
        top.addView(btn("↶ ย้อน") { editView.undo() })
        top.addView(btn("↷ ทำซ้ำ") { editView.redo() })
        top.addView(btn("รีเซ็ต") { resetToAi() })
        top.addView(btn("เสร็จ ✓") { saveAndFinish() }.apply { setBackgroundColor(ACCENT) })
        root.addView(top)

        editView = MaskEditView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            whiteBg = intent.getBooleanExtra("white", false)
            brushPx = 30 * resources.displayMetrics.density
            onEdited = { edited = true }
        }
        root.addView(editView)

        val modeRow1 = row()
        btnEraseMan = btn("🧹 ลบ (มือ)") { setMode(MaskEditView.Mode.ERASE_MANUAL) }
        btnEraseMagic = btn("✨ เมจิกอีเรเซอร์ (ลบอัจฉริยะ)") { setMode(MaskEditView.Mode.ERASE_MAGIC) }
        modeRow1.addView(btnEraseMan)
        modeRow1.addView(btnEraseMagic)
        root.addView(modeRow1)

        val modeRow2 = row()
        btnRestMan = btn("🖌️ ดึงกลับ (มือ)") { setMode(MaskEditView.Mode.RESTORE_MANUAL) }
        btnRestMagic = btn("🪄 ดึงกลับ (อัจฉริยะ)") { setMode(MaskEditView.Mode.RESTORE_MAGIC) }
        modeRow2.addView(btnRestMan)
        modeRow2.addView(btnRestMagic)
        modeRow2.addView(btn("พอดีจอ") { editView.fitToView() })
        root.addView(modeRow2)

        val sizeRow = row()
        val label = TextView(this).apply {
            text = "พู่กัน 30"
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = 30
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = maxOf(p, 4)
                    label.text = "พู่กัน $v"
                    editView.brushPx = v * resources.displayMetrics.density
                    editView.showBrushPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sizeRow.addView(label)
        sizeRow.addView(seek)
        root.addView(sizeRow)

        hint = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(dp(12), dp(2), dp(12), dp(10))
            text = "กำลังโหลดรูป..."
        }
        root.addView(hint)

        setContentView(root)
        setMode(MaskEditView.Mode.ERASE_MANUAL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askDiscard()
        })

        lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) { Img.decode(item.src) to Img.decode(item.mask) }
                editView.setImages(pair.first, pair.second)
                setMode(editView.currentMode)
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, "เปิดรูปไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(GRAY)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun setMode(m: MaskEditView.Mode) {
        editView.currentMode = m
        btnEraseMan.setBackgroundColor(if (m == MaskEditView.Mode.ERASE_MANUAL) ACCENT else GRAY)
        btnEraseMagic.setBackgroundColor(if (m == MaskEditView.Mode.ERASE_MAGIC) ACCENT else GRAY)
        btnRestMan.setBackgroundColor(if (m == MaskEditView.Mode.RESTORE_MANUAL) ACCENT else GRAY)
        btnRestMagic.setBackgroundColor(if (m == MaskEditView.Mode.RESTORE_MAGIC) ACCENT else GRAY)

        hint.text = when (m) {
            MaskEditView.Mode.ERASE_MANUAL -> "🧹 ลบ (มือ): ระบายลบตามนิ้วทันที"
            MaskEditView.Mode.ERASE_MAGIC -> "✨ เมจิกอีเรเซอร์: ระบายทับส่วนเกิน แล้วปล่อยนิ้ว AI จะลบเฉพาะวัตถุ/โทนสีนั้นให้เนียนๆ"
            MaskEditView.Mode.RESTORE_MANUAL -> "🖌️ ดึงกลับ (มือ): ระบายดึงภาพกลับตามนิ้ว"
            MaskEditView.Mode.RESTORE_MAGIC -> "🪄 ดึงกลับอัจฉริยะ: ระบายบริเวณที่ต้องการ แล้วปล่อยนิ้วให้ AI ช่วยเติมภาพส่วนนั้นกลับมา"
        }
    }

    private fun resetToAi() {
        lifecycleScope.launch {
            try {
                val ai = withContext(Dispatchers.IO) { Img.decode(item.aiMask) }
                editView.resetTo(ai)
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, "รีเซ็ตไม่ได้", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun askDiscard() {
        if (!edited) { finish(); return }
        AlertDialog.Builder(this)
            .setMessage("ยังไม่ได้บันทึกการแก้ไข ออกเลยไหม?")
            .setPositiveButton("ออก") { _, _ -> finish() }
            .setNegativeButton("อยู่ต่อ", null)
            .show()
    }

    private fun saveAndFinish() {
        if (saving) return
        val s = editView.src
        val m = editView.mask
        if (s == null || m == null || !edited) { finish(); return }
        saving = true
        hint.text = "กำลังบันทึก..."
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Img.savePng(m, item.mask)
                item.thumb = Img.makeThumb(s, m)
            }
            setResult(RESULT_OK, Intent().putExtra("index", index))
            finish()
        }
    }
}
