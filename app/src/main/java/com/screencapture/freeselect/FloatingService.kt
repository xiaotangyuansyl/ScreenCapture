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
    
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            // 当MediaProjection被停止时（例如用户手动停止），更新状态
            mediaProjection = null
            Toast.makeText(this@FloatingService, "截图权限已停止，请重启服务", Toast.LENGTH_LONG).show()
        }
        
        override fun onSuccess() {
            super.onSuccess()
            // 当MediaProjection成功时
            Toast.makeText(this@FloatingService, "截图权限正常", Toast.LENGTH_SHORT).show()
        }
    }

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

        // 检查是否有传递过来的权限数据
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }

            if (resultCode != -1 && data != null) {
                setupMediaProjection(resultCode, data)
            }
        }

        // 如果没有权限数据且 mediaProjection 为 null，提示用户重新启动服务
        if (mediaProjection == null) {
            // 在某些手机上，服务重启后需要用户重新授权
            handler.post {
                Toast.makeText(this, "服务已启动，如需截图请重启服务以获取权限", Toast.LENGTH_LONG).show()
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
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            // 注册回调以监听权限变化
            mediaProjection?.registerCallback(mediaProjectionCallback, handler)
            
            // 验证MediaProjection是否有效
            if (mediaProjection != null) {
                Toast.makeText(this, "截图权限获取成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "截图权限获取失败", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "权限设置异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        // 检查是否拥有必要的权限
        if (mediaProjection == null) {
            // 在某些手机（如OPPO）上，服务可能在后台被系统回收了MediaProjection
            // 尝试重新启动服务
            Toast.makeText(this, "截图权限异常，请返回应用重新启动服务", Toast.LENGTH_LONG).show()
            // 启动主Activity以便用户可以重新启动服务
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        // 隐藏悬浮按钮
        floatingView?.visibility = View.GONE

        // 延迟执行，确保UI更新完成
        handler.postDelayed({
            captureScreen { bitmap ->
                if (bitmap != null) {
                    showDrawingOverlay(bitmap)
                } else {
                    // 如果截图失败，显示错误信息
                    floatingView?.visibility = View.VISIBLE
                    Toast.makeText(this, "截图失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }, 200)
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

        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            // 创建虚拟显示用于截图
            val tempVirtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCaptureTemp",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader?.surface,
                null,
                handler
            )

            if (tempVirtualDisplay == null) {
                callback(null)
                return
            }

            // 等待屏幕内容渲染
            handler.postDelayed({
                var bitmap: Bitmap? = null
                var image: Image? = null
                try {
                    // 等待几帧以确保显示内容更新
                    Thread.sleep(100)
                    image = imageReader?.acquireLatestImage()
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

                        // 裁剪到实际屏幕大小
                        bitmap = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image?.close()
                    tempVirtualDisplay.release()
                }

                callback(bitmap)
            }, 150)
        } catch (e: Exception) {
            e.printStackTrace()
            callback(null)
        }
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
        // 注销回调并停止MediaProjection
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
}
