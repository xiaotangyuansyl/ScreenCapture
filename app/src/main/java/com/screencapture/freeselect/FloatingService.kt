package com.screencapture.freeselect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FloatingService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var drawingOverlay: DrawingOverlayView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        intent?.let {
            val resultCode = it.getIntExtra("resultCode", -1)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra("data")
            }

            if (resultCode != -1 && data != null) {
                setupMediaProjection(resultCode, data)
            }
        }

        createFloatingButton()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕截图服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持屏幕截图服务运行"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕截图")
            .setContentText("点击悬浮按钮开始圈选截图")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    }

    private fun createFloatingButton() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)

        val captureButton = floatingView?.findViewById<ImageButton>(R.id.captureButton)

        // 拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isClick = false
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        startDrawingMode()
                    }
                    true
                }
                else -> false
            }
        }

        captureButton?.setOnClickListener {
            startDrawingMode()
        }

        windowManager.addView(floatingView, layoutParams)
    }

    private fun startDrawingMode() {
        // 在某些手机上（如OPPO），需要先检查虚拟显示是否还有效
        if (mediaProjection == null) {
            Toast.makeText(this, "截图权限已失效，请重启服务", Toast.LENGTH_LONG).show()
            restartServiceWithPermissionCheck()
            return
        }

        // 隐藏悬浮按钮
        floatingView?.visibility = View.GONE

        // 在OPPO等一些手机上，需要延迟更长时间确保权限稳定
        handler.postDelayed({
            // 再次检查mediaProjection是否有效
            if (mediaProjection == null) {
                floatingView?.visibility = View.VISIBLE
                Toast.makeText(this, "截图权限异常，请重启服务", Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            
            captureScreen { bitmap ->
                if (bitmap != null) {
                    showDrawingOverlay(bitmap)
                } else {
                    floatingView?.visibility = View.VISIBLE
                    Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
                }
            }
        }, 300) // 增加延迟时间以适应某些手机
    }

    private fun restartServiceWithPermissionCheck() {
        // 在某些手机上（如OPPO），需要通知用户重新启动服务
        Toast.makeText(this, "请返回应用，停止并重新启动服务", Toast.LENGTH_LONG).show()
    }

    private fun captureScreen(callback: (Bitmap?) -> Unit) {
        // 检查mediaProjection是否仍然有效
        if (mediaProjection == null) {
            callback(null)
            return
        }

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // 临时创建虚拟显示
        val tempVirtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        // 等待屏幕内容渲染
        handler.postDelayed({
            var bitmap: Bitmap? = null
            try {
                val image: Image? = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bmp = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    image.close()

                    // 裁剪到实际屏幕大小
                    bitmap = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                tempVirtualDisplay?.release()
            }

            callback(bitmap)
        }, 200) // 增加延时以适应某些手机
    }

    private fun showDrawingOverlay(bitmap: Bitmap) {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        drawingOverlay = DrawingOverlayView(this, bitmap) { croppedBitmap ->
            // 回调：裁剪完成或取消
            removeDrawingOverlay()
            floatingView?.visibility = View.VISIBLE

            croppedBitmap?.let {
                ImageSaver.saveImage(this, it) { success, path ->
                    if (success) {
                        Toast.makeText(this, "图片已保存: $path", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        windowManager.addView(drawingOverlay, layoutParams)
    }

    private fun removeDrawingOverlay() {
        drawingOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        drawingOverlay = null
    }

    override fun onDestroy() {
        isRunning = false
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        removeDrawingOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
