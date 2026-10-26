package com.screenai

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var pendingCapture: ((ByteArray?) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callbackTimeout: Runnable? = null

    companion object {
        const val EXTRA_RESULT_CODE = "screen_ai_result_code"
        const val EXTRA_RESULT_DATA = "screen_ai_result_data"
        private const val CHANNEL_ID = "screen_ai_capture"
        @Volatile private var active: ScreenCaptureService? = null

        fun isRunning() = active != null

        fun requestCapture(callback: (ByteArray?) -> Unit): Boolean {
            val service = active ?: return false
            synchronized(service) {
                service.callbackTimeout?.let { service.mainHandler.removeCallbacks(it) }
                service.pendingCapture = callback
                val timeout = Runnable {
                    val cb: ((ByteArray?) -> Unit)?
                    synchronized(service) {
                        cb = service.pendingCapture
                        service.pendingCapture = null
                        service.callbackTimeout = null
                    }
                    cb?.invoke(null)
                }
                service.callbackTimeout = timeout
                service.mainHandler.postDelayed(timeout, 7000)
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        createNotificationChannel()
        workerThread = HandlerThread("ScreenAI-Capture").also { it.start() }
        worker = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) { stopSelf(); return START_NOT_STICKY }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android has already stopped this projection; clear our reference before cleanup.
                    projection = null
                    releaseCaptureResources()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, mainHandler)
            startVirtualDisplay()
        } catch (e: Exception) {
            failPendingCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startVirtualDisplay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            val callback: ((ByteArray?) -> Unit)?
            synchronized(this) {
                callback = pendingCapture
                if (callback != null) {
                    pendingCapture = null
                    callbackTimeout?.let { mainHandler.removeCallbacks(it) }
                    callbackTimeout = null
                }
            }
            if (callback == null) {
                image.close()
                return@setOnImageAvailableListener
            }
            var bytes: ByteArray? = null
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = rowStride / pixelStride
                val source = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                source.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (paddedWidth != image.width) Bitmap.createBitmap(source, 0, 0, image.width, image.height) else source
                val output = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, output)
                bytes = output.toByteArray()
                if (cropped !== source) cropped.recycle()
                source.recycle()
                output.close()
            } catch (_: Exception) {
                bytes = null
            } finally {
                image.close()
            }
            callback?.invoke(bytes)
        }, worker)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenAI", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, worker
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen AI screen capture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible notification while Screen AI screen capture is active"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Screen AI is ready")
            .setContentText("Screen capture is active. Tap the system sharing indicator to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun failPendingCapture() {
        val cb = synchronized(this) { val x = pendingCapture; pendingCapture = null; x }
        cb?.invoke(null)
    }

    private fun releaseCaptureResources() {
        val oldProjection = projection
        projection = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { oldProjection?.stop() } catch (_: Exception) {}
        failPendingCapture()
    }

    override fun onDestroy() {
        releaseCaptureResources()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        active = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
