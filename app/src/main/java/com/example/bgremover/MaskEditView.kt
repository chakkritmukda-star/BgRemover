package com.example.bgremover

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class MaskEditView(context: Context) : View(context) {

    enum class Mode {
        MAGIC_ERASER,
        ERASE_MANUAL,
        RESTORE_MANUAL,
        SMART_CUTOUT,
        PAN
    }

    private data class HistorySnapshot(
        val colorBmp: Bitmap,
        val alphaBmp: Bitmap
    )

    var mode: Mode = Mode.MAGIC_ERASER
        set(value) {
            field = value
            clearStrokeOverlay()
            invalidate()
        }

    var brushRadiusScreen: Float = 44f
        set(value) {
            field = value
            showBrushPreviewUntil = System.currentTimeMillis() + 900L
            invalidate()
        }

    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onMagicEraseStrokeFinished: ((strokeMask: Bitmap) -> Unit)? = null

    private var initialOriginalBmp: Bitmap? = null
    private var workingColorBmp: Bitmap? = null
    private var alphaMaskBmp: Bitmap? = null
    private var strokeOverlayBmp: Bitmap? = null

    private var alphaCanvas: Canvas? = null
    private var strokeCanvas: Canvas? = null

    private var isInitialFitDone = false
    private var isComparingOriginal = false
    private var isBusy = false
    private var showBrushPreviewUntil = 0L

    private val viewMatrix = Matrix()
    private val invMatrix = Matrix()

    private val drawPath = Path()
    private var isDrawingStroke = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastImgX = 0f
    private var lastImgY = 0f

    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()
    private val maxHistory = 10

    private val paintEraseAlpha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val paintRestoreAlpha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val paintStrokeMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val paintMaskDstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val paintMagicTint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.parseColor("#B3A855F7"), PorterDuff.Mode.SRC_IN)
    }

    private val paintSmartTint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.parseColor("#99EF4444"), PorterDuff.Mode.SRC_IN)
    }

    private val paintBrushRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }

    private val paintBrushFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#55A855F7")
    }

    private val paintCheckerA = Paint().apply { color = Color.parseColor("#CBD5E1") }
    private val paintCheckerB = Paint().apply { color = Color.parseColor("#F8FAFC") }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.coerceIn(0.5f, 2.0f)
            viewMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setImages(original: Bitmap, currentResult: Bitmap, hasBgRemoved: Boolean) {
        val w = original.width
        val h = original.height
        initialOriginalBmp = original.copy(Bitmap.Config.ARGB_8888, false)

        if (hasBgRemoved) {
            workingColorBmp = original.copy(Bitmap.Config.ARGB_8888, true)
            val origPx = IntArray(w * h)
            val curPx = IntArray(w * h)
            val alphaPx = IntArray(w * h)
            workingColorBmp!!.getPixels(origPx, 0, w, 0, 0, w, h)
            currentResult.getPixels(curPx, 0, w, 0, 0, w, h)
            for (i in curPx.indices) {
                val a = (curPx[i] ushr 24) and 0xFF
                alphaPx[i] = a shl 24
                if (a > 16) {
                    origPx[i] = (0xFF shl 24) or (curPx[i] and 0x00FFFFFF)
                }
            }
            workingColorBmp!!.setPixels(origPx, 0, w, 0, 0, w, h)
            alphaMaskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(alphaPx, 0, w, 0, 0, w, h)
            }
        } else {
            workingColorBmp = currentResult.copy(Bitmap.Config.ARGB_8888, true)
            alphaMaskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
        }

        alphaCanvas = Canvas(alphaMaskBmp!!)
        strokeOverlayBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        strokeCanvas = Canvas(strokeOverlayBmp!!)

        undoStack.clear()
        redoStack.clear()
        pushUndoState()
        isInitialFitDone = false
        requestLayout()
        invalidate()
    }

    fun setProcessingBusy(busy: Boolean) {
        isBusy = busy
        if (!busy) clearStrokeOverlay()
        invalidate()
    }

    fun setCompareOriginal(compare: Boolean) {
        isComparingOriginal = compare
        invalidate()
    }

    fun applyInpaintedBitmap(newColorBmp: Bitmap) {
        workingColorBmp = newColorBmp
        clearStrokeOverlay()
        pushUndoState()
        invalidate()
    }

    private fun clearStrokeOverlay() {
        strokeOverlayBmp?.eraseColor(Color.TRANSPARENT)
    }

    private fun pushUndoState() {
        val c = workingColorBmp ?: return
        val a = alphaMaskBmp ?: return
        if (undoStack.size >= maxHistory) {
            val dropped = undoStack.removeFirst()
            dropped.colorBmp.recycle()
            dropped.alphaBmp.recycle()
        }
        undoStack.addLast(
            HistorySnapshot(
                colorBmp = c.copy(Bitmap.Config.ARGB_8888, true),
                alphaBmp = a.copy(Bitmap.Config.ARGB_8888, true)
            )
        )
        while (redoStack.isNotEmpty()) {
            val r = redoStack.removeLast()
            r.colorBmp.recycle()
            r.alphaBmp.recycle()
        }
        notifyHistory()
    }

    fun undo() {
        if (isBusy || undoStack.size <= 1) return
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        restoreSnapshot(undoStack.last())
        notifyHistory()
    }

    fun redo() {
        if (isBusy || redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        restoreSnapshot(next)
        notifyHistory()
    }

    private fun restoreSnapshot(snap: HistorySnapshot) {
        workingColorBmp = snap.colorBmp.copy(Bitmap.Config.ARGB_8888, true)
        alphaMaskBmp = snap.alphaBmp.copy(Bitmap.Config.ARGB_8888, true)
        alphaCanvas = Canvas(alphaMaskBmp!!)
        clearStrokeOverlay()
        invalidate()
    }

    private fun notifyHistory() {
        onHistoryChanged?.invoke(undoStack.size > 1, redoStack.isNotEmpty())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bmp = workingColorBmp ?: return
        if (!isInitialFitDone && w > 0 && h > 0) {
            val scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height) * 0.94f
            val dx = (w - bmp.width * scale) / 2f
            val dy = (h - bmp.height * scale) / 2f
            viewMatrix.reset()
            viewMatrix.postScale(scale, scale)
            viewMatrix.postTranslate(dx, dy)
            isInitialFitDone = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colorBmp = workingColorBmp ?: return
        val maskBmp = alphaMaskBmp ?: return

        drawCheckerboard(canvas)

        if (isComparingOriginal && initialOriginalBmp != null) {
            canvas.save()
            canvas.concat(viewMatrix)
            canvas.drawBitmap(initialOriginalBmp!!, 0f, 0f, bmpPaint)
            canvas.restore()
            return
        }

        val saveLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.concat(viewMatrix)
        canvas.drawBitmap(colorBmp, 0f, 0f, bmpPaint)
        canvas.drawBitmap(maskBmp, 0f, 0f, paintMaskDstIn)
        strokeOverlayBmp?.let { overlay ->
            val tint = if (mode == Mode.MAGIC_ERASER) paintMagicTint else paintSmartTint
            canvas.drawBitmap(overlay, 0f, 0f, tint)
        }
        canvas.restoreToCount(saveLayer)

        if (isDrawingStroke && mode != Mode.PAN) {
            canvas.drawCircle(lastTouchX, lastTouchY, brushRadiusScreen, paintBrushFill)
            canvas.drawCircle(lastTouchX, lastTouchY, brushRadiusScreen, paintBrushRing)
            drawMagnifierLoupe(canvas)
        } else if (System.currentTimeMillis() < showBrushPreviewUntil) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, brushRadiusScreen, paintBrushFill)
            canvas.drawCircle(cx, cy, brushRadiusScreen, paintBrushRing)
        }
    }

    private fun drawMagnifierLoupe(canvas: Canvas) {
        val colorBmp = workingColorBmp ?: return
        val maskBmp = alphaMaskBmp ?: return
        val loupeSize = 230f
        val margin = 24f
        val placeRight = lastTouchX < loupeSize + 80f && lastTouchY < loupeSize + 80f
        val left = if (placeRight) width - loupeSize - margin else margin
        val top = margin
        val cx = left + loupeSize / 2f
        val cy = top + loupeSize / 2f
        val radius = loupeSize / 2f

        val clipSave = canvas.save()
        val circlePath = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(circlePath)
        canvas.drawColor(Color.parseColor("#1E293B"))

        val zoom = currentScale() * 1.8f
        val loupeMatrix = Matrix().apply {
            postTranslate(-lastImgX, -lastImgY)
            postScale(zoom, zoom)
            postTranslate(cx, cy)
        }

        val layer = canvas.saveLayer(left, top, left + loupeSize, top + loupeSize, null)
        canvas.concat(loupeMatrix)
        canvas.drawBitmap(colorBmp, 0f, 0f, bmpPaint)
        canvas.drawBitmap(maskBmp, 0f, 0f, paintMaskDstIn)
        strokeOverlayBmp?.let {
            val tint = if (mode == Mode.MAGIC_ERASER) paintMagicTint else paintSmartTint
            canvas.drawBitmap(it, 0f, 0f, tint)
        }
        canvas.restoreToCount(layer)

        val imgBrushR = (brushRadiusScreen / currentScale()) * zoom
        canvas.drawCircle(cx, cy, imgBrushR, paintBrushRing)
        canvas.restoreToCount(clipSave)

        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#A855F7")
        })
    }

    private fun drawCheckerboard(canvas: Canvas) {
        val step = 32
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val p = if (((x / step) + (y / step)) % 2 == 0) paintCheckerA else paintCheckerB
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), (y + step).toFloat(), p)
            }
        }
    }

    private fun currentScale(): Float {
        val vals = FloatArray(9)
        viewMatrix.getValues(vals)
        return hypot(vals[Matrix.MSCALE_X], vals[Matrix.MSKEW_X]).coerceAtLeast(0.05f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isBusy) return true
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount > 1 || mode == Mode.PAN) {
            if (isDrawingStroke) {
                isDrawingStroke = false
                clearStrokeOverlay()
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        viewMatrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            return true
        }

        viewMatrix.invert(invMatrix)
        val pt = floatArrayOf(event.x, event.y)
        invMatrix.mapPoints(pt)
        val imgX = pt[0]
        val imgY = pt[1]
        val strokeWidthImg = (brushRadiusScreen * 2f) / currentScale()

        paintEraseAlpha.strokeWidth = strokeWidthImg
        paintRestoreAlpha.strokeWidth = strokeWidthImg
        paintStrokeMask.strokeWidth = strokeWidthImg

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawingStroke = true
                lastTouchX = event.x
                lastTouchY = event.y
                lastImgX = imgX
                lastImgY = imgY
                drawPath.reset()
                drawPath.moveTo(imgX, imgY)
                drawPointOrPath(imgX, imgY, isSingleTap = true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawingStroke) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    lastImgX = imgX
                    lastImgY = imgY
                    drawPath.lineTo(imgX, imgY)
                    drawPointOrPath(imgX, imgY, isSingleTap = false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawingStroke) {
                    isDrawingStroke = false
                    when (mode) {
                        Mode.MAGIC_ERASER -> {
                            val strokeCopy = strokeOverlayBmp?.copy(Bitmap.Config.ARGB_8888, false)
                            if (strokeCopy != null) {
                                onMagicEraseStrokeFinished?.invoke(strokeCopy)
                            }
                        }
                        Mode.SMART_CUTOUT -> {
                            applySeedColorCutout()
                            clearStrokeOverlay()
                            pushUndoState()
                        }
                        Mode.ERASE_MANUAL, Mode.RESTORE_MANUAL -> {
                            pushUndoState()
                        }
                        Mode.PAN -> {}
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    private fun drawPointOrPath(imgX: Float, imgY: Float, isSingleTap: Boolean) {
        when (mode) {
            Mode.MAGIC_ERASER, Mode.SMART_CUTOUT -> {
                val sc = strokeCanvas ?: return
                if (isSingleTap) {
                    sc.drawPoint(imgX, imgY, paintStrokeMask)
                } else {
                    sc.drawPath(drawPath, paintStrokeMask)
                }
            }
            Mode.ERASE_MANUAL -> {
                val ac = alphaCanvas ?: return
                if (isSingleTap) ac.drawPoint(imgX, imgY, paintEraseAlpha)
                else ac.drawPath(drawPath, paintEraseAlpha)
            }
            Mode.RESTORE_MANUAL -> {
                val ac = alphaCanvas ?: return
                if (isSingleTap) ac.drawPoint(imgX, imgY, paintRestoreAlpha)
                else ac.drawPath(drawPath, paintRestoreAlpha)
            }
            Mode.PAN -> {}
        }
    }

    private fun applySeedColorCutout() {
        val colorBmp = workingColorBmp ?: return
        val maskBmp = alphaMaskBmp ?: return
        val strokeBmp = strokeOverlayBmp ?: return

        val w = colorBmp.width
        val h = colorBmp.height
        val colorPx = IntArray(w * h)
        val alphaPx = IntArray(w * h)
        val strokePx = IntArray(w * h)

        colorBmp.getPixels(colorPx, 0, w, 0, 0, w, h)
        ma
