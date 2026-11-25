package com.example.bgremover

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.math.hypot

class EditorActivity : AppCompatActivity() {

    private lateinit var editView: MaskEditView
    private lateinit var item: Item
    private lateinit var btnErase: Button
    private lateinit var btnRestore: Button
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
        if (found == null) {
            finish()
            return
        }
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

        val modeRow = row()
        btnErase = btn("ลบส่วนเกิน") { setMode(false) }
        btnRestore = btn("เติมกลับ") { setMode(true) }
        modeRow.addView(btnErase)
        modeRow.addView(btnRestore)
        modeRow.addView(btn("พอดีจอ") { editView.fitToView() })
        root.addView(modeRow)

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
        setMode(false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askDiscard()
        })

        lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) { Img.decode(item.src) to Img.decode(item.mask) }
                editView.setImages(pair.first, pair.second)
                setMode(editView.restoreMode)
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
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(GRAY)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun setMode(restore: Boolean) {
        editView.restoreMode = restore
        btnErase.setBackgroundColor(if (!restore) ACCENT else GRAY)
        btnRestore.setBackgroundColor(if (restore) ACCENT else GRAY)
        hint.text = if (restore)
            "เติมกลับ: ระบายตรงที่อยากเอากลับมา (ภาพจาง ๆ คือภาพต้นฉบับ) • 2 นิ้ว = ซูม/เลื่อน"
        else
            "ลบส่วนเกิน: ระบายทับส่วนที่ไม่ต้องการ เช่น มือ เงา • 2 นิ้ว = ซูม/เลื่อน"
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
        if (!edited) {
            finish()
            return
        }
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
        if (s == null || m == null || !edited) {
            finish()
            return
        }
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

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MaskEditView(context: Context) : View(context) {

    var src: Bitmap? = null
        private set
    var mask: Bitmap? = null
        private set
    private var maskCanvas: Canvas? = null
    private var cutout: Bitmap? = null
    private var cutoutCanvas: Canvas? = null

    var restoreMode = false
        set(v) { field = v; invalidate() }
    var whiteBg = false
        set(v) { field = v; invalidate() }
    var brushPx = 60f
    var onEdited: (() -> Unit)? = null

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val MAX_UNDO = 8

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private val mv = FloatArray(9)
    private var minScale = 0.1f
    private var maxScale = 20f
    private val imgRect = RectF()

    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
    private val whitePaint = Paint().apply { color = Color.WHITE }
    private val checkerPaint = Paint().apply {
        shader = BitmapShader(Img.checkerTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val ringWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE
    }
    private val ringBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE
    }
    private val loupePath = Path()
    private val viewBg = Color.rgb(40, 40, 40)

    private var drawing = false
    private var gesture = false
    private var needReset = false
    private var touchX = 0f
    private var touchY = 0f
    private var lastImgX = 0f
    private var lastImgY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDist = 0f
    private var previewCenter = false
    private val hidePreview = Runnable { previewCenter = false; invalidate() }

    fun setImages(s: Bitmap, m: Bitmap) {
        src = s
        setMaskInternal(m)
        cutout = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        cutoutCanvas = Canvas(cutout!!)
        imgRect.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
        refreshAll()
        fitToView()
    }

    private fun setMaskInternal(b: Bitmap) {
        mask = b
        maskCanvas = Canvas(b)
    }

    fun fitToView() {
        val s = src ?: return
        if (width == 0 || height == 0) return
        val sc = minOf(width.toFloat() / s.width, height.toFloat() / s.height) * 0.94f
        viewMatrix.reset()
        viewMatrix.postScale(sc, sc)
        viewMatrix.postTranslate((width - s.width * sc) / 2f, (height - s.height * sc) / 2f)
        viewMatrix.invert(inverse)
        minScale = sc * 0.5f
        maxScale = sc * 15f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    fun showBrushPreview() {
        previewCenter = true
        removeCallbacks(hidePreview)
        postDelayed(hidePreview, 800)
        invalidate()
    }

    fun undo() {
        if (drawing || undoStack.isEmpty() || mask == null) return
        redoStack.addLast(mask!!)
        setMaskInternal(undoStack.removeLast())
        refreshAll()
        onEdited?.invoke()
    }

    fun redo() {
        if (drawing || redoStack.isEmpty() || mask == null) return
        undoStack.addLast(mask!!)
        setMaskInternal(redoStack.removeLast())
        refreshAll()
        onEdited?.invoke()
    }

    fun resetTo(ai: Bitmap) {
        val m = mask ?: return
        undoStack.addLast(m)
        trimUndo()
        clearRedo()
        setMaskInternal(ai)
        refreshAll()
        onEdited?.invoke()
    }

    private fun trimUndo() {
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst().recycle()
    }

    private fun clearRedo() {
        redoStack.forEach { it.recycle() }
        redoStack.clear()
    }

    private fun curScale(): Float {
        viewMatrix.getValues(mv)
        return mv[Matrix.MSCALE_X]
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p
    }

    private fun refreshAll() {
        val s = src ?: return
        refreshRect(0, 0, s.width, s.height)
        invalidate()
    }

    private fun refresh(x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
        val s = src ?: return
        val l = (minOf(x1, x2) - r - 2).toInt().coerceAtLeast(0)
        val t = (minOf(y1, y2) - r - 2).toInt().coerceAtLeast(0)
        val rr = (maxOf(x1, x2) + r + 2).toInt().coerceAtMost(s.width)
        val b = (maxOf(y1, y2) + r + 2).toInt().coerceAtMost(s.height)
        if (l < rr && t < b) refreshRect(l, t, rr, b)
    }

    private fun refreshRect(l: Int, t: Int, r: Int, b: Int) {
        val s = src ?: return
        val m = mask ?: return
        val c = cutoutCanvas ?: return
        c.save()
        c.clipRect(l, t, r, b)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(s, 0f, 0f, null)
        c.drawBitmap(m, 0f, 0f, dstIn)
        c.restore()
    }

    private fun beginStroke(x: Float, y: Float) {
        val m = mask ?: return
        undoStack.addLast(m.copy(Bitmap.Config.ARGB_8888, true))
        trimUndo()
        drawing = true
        val w = brushPx / curScale()
        val mode = if (restoreMode) null else clearMode
        strokePaint.strokeWidth = w
        strokePaint.xfermode = mode
        dotPaint.xfermode = mode
        val p = toImage(x, y)
        lastImgX = p[0]
        lastImgY = p[1]
        maskCanvas?.drawCircle(lastImgX, lastImgY, w / 2f, dotPaint)
        refresh(lastImgX, lastImgY, lastImgX, lastImgY, w / 2f)
        invalidate()
    }

    private fun continueStroke(x: Float, y: Float) {
        val p = toImage(x, y)
        maskCanvas?.drawLine(lastImgX, lastImgY, p[0], p[1], strokePaint)
        refresh(lastImgX, lastImgY, p[0], p[1], strokePaint.strokeWidth / 2f)
        lastImgX = p[0]
        lastImgY = p[1]
        invalidate()
    }

    private fun endStroke() {
        drawing = false
        clearRedo()
        onEdited?.invoke()
    }

    private fun cancelStroke() {
        drawing = false
        if (undoStack.isNotEmpty()) {
            val old = mask
            setMaskInternal(undoStack.removeLast())
            old?.recycle()
            refreshAll()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (src == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = false
                touchX = e.x
                touchY = e.y
                beginStroke(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (drawing) cancelStroke()
                gesture = true
                needReset = true
            }
            MotionEvent.ACTION_POINTER_UP -> needReset = true
            MotionEvent.ACTION_MOVE -> {
                if (gesture) {
                    if (e.pointerCount >= 2) {
                        val mx = (e.getX(0) + e.getX(1)) / 2f
                        val my = (e.getY(0) + e.getY(1)) / 2f
                        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        if (!needReset) {
                            viewMatrix.postTranslate(mx - lastMidX, my - lastMidY)
                            if (lastDist > 10f && d > 10f) {
                                val cur = curScale()
                                val f = (cur * d / lastDist).coerceIn(minScale, maxScale) / cur
                                viewMatrix.postScale(f, f, mx, my)
                            }
                            viewMatrix.invert(inverse)
                            invalidate()
                        }
                        needReset = false
                        lastMidX = mx
                        lastMidY = my
                        lastDist = d
                    }
                } else if (drawing) {
                    touchX = e.x
                    touchY = e.y
                    continueStroke(e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drawing) endStroke()
                gesture = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawing) cancelStroke()
                gesture = false
                invalidate()
            }
        }
        return true
    }

    private fun drawContent(c: Canvas) {
        val s = src ?: return
        val d = cutout ?: return
        c.save()
        c.concat(viewMatrix)
        c.drawRect(imgRect, if (whiteBg) whitePaint else checkerPaint)
        if (restoreMode) c.drawBitmap(s, 0f, 0f, ghostPaint)
        c.drawBitmap(d, 0f, 0f, bmpPaint)
        c.restore()
    }

    private fun drawRing(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r, ringWhite)
        c.drawCircle(x, y, r, ringBlack)
    }

    private fun drawLoupe(c: Canvas) {
        val size = minOf(width, height) * 0.34f
        val r = size / 2f
        val margin = 24f
        var cx = margin + r
        val cy = margin + r
        if (touchX < margin + size + 60 && touchY < margin + size + 60) cx = width - margin - r

        loupePath.reset()
        loupePath.addCircle(cx, cy, r, Path.Direction.CW)
        c.save()
        c.clipPath(loupePath)
        c.drawColor(viewBg)
        c.translate(cx, cy)
        c.scale(2f, 2f)
        c.translate(-touchX, -touchY)
        drawContent(c)
        c.restore()
        c.drawCircle(cx, cy, r, loupeBorder)
        drawRing(c, cx, cy, brushPx)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(viewBg)
        if (src == null) return
        drawContent(canvas)
        if (drawing) {
            drawRing(canvas, touchX, touchY, brushPx / 2f)
            drawLoupe(canvas)
        } else if (previewCenter) {
            drawRing(canvas, width / 2f, height / 2f, brushPx / 2f)
        }
    }
}
