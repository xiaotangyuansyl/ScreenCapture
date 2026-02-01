package com.screencapture.freeselect

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout

class DrawingOverlayView(
    context: Context,
    private val screenshotBitmap: Bitmap,
    private val onComplete: (Bitmap?) -> Unit
) : FrameLayout(context) {

    private val path = Path()
    private val pathPoints = mutableListOf<PointF>()
    private var isDrawing = false
    private var pathClosed = false

    private val drawPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(50, 255, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private val canvas = Canvas()
    private var drawBitmap: Bitmap? = null

    private var buttonContainer: LinearLayout? = null

    init {
        setWillNotDraw(false)
        setupButtons()
    }

    private fun setupButtons() {
        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE

            val buttonSize = (56 * resources.displayMetrics.density).toInt()
            val margin = (8 * resources.displayMetrics.density).toInt()

            // 确认按钮
            val confirmButton = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                setOnClickListener { confirmSelection() }
            }
            val confirmParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(margin, margin, margin, margin)
            }
            addView(confirmButton, confirmParams)

            // 取消按钮
            val cancelButton = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                setOnClickListener { cancelSelection() }
            }
            val cancelParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(margin, margin, margin, margin)
            }
            addView(cancelButton, cancelParams)

            // 重画按钮
            val redrawButton = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                setOnClickListener { resetDrawing() }
            }
            val redrawParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(margin, margin, margin, margin)
            }
            addView(redrawButton, redrawParams)
        }

        val containerParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (100 * resources.displayMetrics.density).toInt()
        }
        addView(buttonContainer, containerParams)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawBitmap?.recycle()
        drawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(drawBitmap)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制截图作为背景
        val srcRect = Rect(0, 0, screenshotBitmap.width, screenshotBitmap.height)
        val dstRect = Rect(0, 0, width, height)
        canvas.drawBitmap(screenshotBitmap, srcRect, dstRect, bitmapPaint)

        // 绘制半透明遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // 如果路径已闭合，在选中区域内显示清晰图像
        if (pathClosed && pathPoints.size > 2) {
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(screenshotBitmap, srcRect, dstRect, bitmapPaint)
            canvas.restore()
        }

        // 绘制选择路径
        if (pathPoints.isNotEmpty()) {
            if (pathClosed) {
                canvas.drawPath(path, fillPaint)
            }
            canvas.drawPath(path, drawPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pathClosed) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                pathPoints.clear()
                path.reset()
                path.moveTo(event.x, event.y)
                pathPoints.add(PointF(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    path.lineTo(event.x, event.y)
                    pathPoints.add(PointF(event.x, event.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing && pathPoints.size > 10) {
                    // 闭合路径
                    path.close()
                    pathClosed = true
                    buttonContainer?.visibility = View.VISIBLE
                    invalidate()
                }
                isDrawing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetDrawing() {
        path.reset()
        pathPoints.clear()
        pathClosed = false
        buttonContainer?.visibility = View.GONE
        invalidate()
    }

    private fun confirmSelection() {
        if (!pathClosed || pathPoints.size < 3) {
            onComplete(null)
            return
        }

        val croppedBitmap = cropBitmapToPath()
        onComplete(croppedBitmap)
    }

    private fun cancelSelection() {
        onComplete(null)
    }

    private fun cropBitmapToPath(): Bitmap? {
        try {
            // 计算路径的边界
            val bounds = RectF()
            path.computeBounds(bounds, true)

            // 确保边界在屏幕范围内
            bounds.left = bounds.left.coerceAtLeast(0f)
            bounds.top = bounds.top.coerceAtLeast(0f)
            bounds.right = bounds.right.coerceAtMost(width.toFloat())
            bounds.bottom = bounds.bottom.coerceAtMost(height.toFloat())

            val boundsWidth = bounds.width().toInt()
            val boundsHeight = bounds.height().toInt()

            if (boundsWidth <= 0 || boundsHeight <= 0) return null

            // 缩放路径以匹配原始截图尺寸
            val scaleX = screenshotBitmap.width.toFloat() / width
            val scaleY = screenshotBitmap.height.toFloat() / height

            val scaledPath = Path(path)
            val matrix = Matrix()
            matrix.setScale(scaleX, scaleY)
            scaledPath.transform(matrix)

            val scaledBounds = RectF()
            scaledPath.computeBounds(scaledBounds, true)

            // 平移路径到原点
            val translateMatrix = Matrix()
            translateMatrix.setTranslate(-scaledBounds.left, -scaledBounds.top)
            scaledPath.transform(translateMatrix)

            val resultWidth = scaledBounds.width().toInt()
            val resultHeight = scaledBounds.height().toInt()

            if (resultWidth <= 0 || resultHeight <= 0) return null

            // 创建结果位图（带透明背景）
            val resultBitmap = Bitmap.createBitmap(resultWidth, resultHeight, Bitmap.Config.ARGB_8888)
            val resultCanvas = Canvas(resultBitmap)

            // 使用路径作为裁剪区域
            resultCanvas.clipPath(scaledPath)

            // 绘制截图的对应区域
            val srcRect = Rect(
                scaledBounds.left.toInt(),
                scaledBounds.top.toInt(),
                scaledBounds.right.toInt(),
                scaledBounds.bottom.toInt()
            )
            val dstRect = Rect(0, 0, resultWidth, resultHeight)
            resultCanvas.drawBitmap(screenshotBitmap, srcRect, dstRect, bitmapPaint)

            return resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drawBitmap?.recycle()
        drawBitmap = null
    }
}
