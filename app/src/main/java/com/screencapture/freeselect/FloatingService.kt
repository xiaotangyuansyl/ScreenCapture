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
            // 在某些手机上（如OPPO、华为等），服务重启后需要用户重新授权
            handler.post {
                Toast.makeText(this, "请前往应用设置，找到电池优化选项，将本应用设为"不叕制"，然后重启服务获取截图权限", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "截图权限已失效，请重新启动服务获取截图权限", Toast.LENGTH_LONG).show()
            // 启动主Activity以便用户可以重新启动服务
               val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            return
        }

        // 额外检查：尝试创建一个临时虚拟显示器以验证MediaProjection是否仍然有效
        try {
            val dummyImageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1)
            val dummyVirtualDisplay = mediaProjection?.createVirtualDisplay(
                "TestDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                dummyImageReader.surface,
                null,
                handler
            )
            
            // 如果能成功创建虚拟显示器，则说明MediaProjection仍然有效
            dummyVirtualDisplay?.release()
            dummyImageReader.close()
        } catch (e: Exception) {
            // 如果无法创�