package com.example.screengrab

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Foreground service that owns the MediaProjection session, the floating bubble,
 * and the post-capture preview. Everything stays on-device.
 *
 * Design notes:
 *  - Android 14+ allows only ONE VirtualDisplay per projection token, so we create it once
 *    and keep the most recent frame in [latestImage] instead of re-creating a display per shot.
 *  - The bubble is hidden for ~300 ms before reading the frame so it isn't in the screenshot.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.screengrab.START"
        const val ACTION_CAPTURE = "com.example.screengrab.CAPTURE"
        const val ACTION_STOP = "com.example.screengrab.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
        private const val AUTO_SAVE_MS = 8000L

        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null   // newest frame, held open until replaced
    private var width = 0
    private var height = 0
    private var dpi = 0

    private var bubble: View? = null
    private var preview: View? = null
    private var pendingBitmap: Bitmap? = null
    private val autoSave = Runnable { commitPreview() }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_CAPTURE -> takeScreenshot()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- setup

    private fun startCapture(intent: Intent) {
        if (projection != null) return // already running

        // Must be in the foreground (type mediaProjection) BEFORE getMediaProjection() on Android 14+.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) { stopSelf(); return }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) { stopSelf(); return }
        projection = p

        // Android 14+ requires a callback to be registered before createVirtualDisplay().
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } } // user revoked via system UI
        }, handler)

        val size = screenSize()
        width = size.width; height = size.height
        dpi = resources.displayMetrics.densityDpi
        val reader = createReader()
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "ScreenGrab", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        showBubble()
        running = true
    }

    private fun createReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latestImage?.close()
                latestImage = img
            }, handler)
        }

    @Suppress("DEPRECATION")
    private fun screenSize(): Size =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { Size(it.width(), it.height()) }
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Size(dm.widthPixels, dm.heightPixels)
        }

    /** Handle rotation: resize the existing virtual display and swap in a matching reader. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = virtualDisplay ?: return
        val s = screenSize()
        if (s.width == width && s.height == height) return
        width = s.width; height = s.height
        latestImage?.close(); latestImage = null
        val old = imageReader
        val fresh = createReader()
        vd.resize(width, height, dpi)
        vd.surface = fresh.surface
        imageReader = fresh
        old?.close()
    }

    // ---------------------------------------------------------------- capture

    private fun takeScreenshot() {
        if (projection == null) return
        commitPreview()                       // auto-save any preview still showing
        bubble?.visibility = View.INVISIBLE   // keep the bubble out of the picture
        handler.postDelayed({
            val bmp = latestImage?.let { runCatching { toBitmap(it) }.getOrNull() }
            bubble?.visibility = View.VISIBLE
            if (bmp == null) toast(R.string.capture_failed) else showPreview(bmp)
        }, 300)
    }

    /** Converts an RGBA_8888 [Image] to a Bitmap, removing row padding added by the driver. */
    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        plane.buffer.rewind() // the same frame may be read more than once
        val padded = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    // ---------------------------------------------------------------- floating bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(52)
        val v = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61E88E5.toInt())
            }
            contentDescription = getString(R.string.notif_capture)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = height / 3 }

        // Tap = capture, drag = move
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) { lp.x = startX + dx; lp.y = startY + dy; wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) takeScreenshot(); true }
                else -> false
            }
        }
        wm.addView(v, lp)
        bubble = v
    }

    // ---------------------------------------------------------------- preview: Save / Crop / Delete

    private fun showPreview(bmp: Bitmap) {
        pendingBitmap = bmp
        val ctx = ContextThemeWrapper(this, R.style.AppTheme)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_preview, null)

        val tw = 360
        val thumb = Bitmap.createScaledBitmap(bmp, tw, (bmp.height * tw / bmp.width.toFloat()).toInt(), true)
        v.findViewById<ImageView>(R.id.thumb).setImageBitmap(thumb)
        v.findViewById<View>(R.id.actSave).setOnClickListener { commitPreview() }
        v.findViewById<View>(R.id.actCrop).setOnClickListener { startCrop() }
        v.findViewById<View>(R.id.actDelete).setOnClickListener { discardPreview() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(16); y = dp(120) }
        wm.addView(v, lp)
        preview = v
        handler.postDelayed(autoSave, AUTO_SAVE_MS) // never lose a capture: auto-save if ignored
    }

    private fun removePreview() {
        handler.removeCallbacks(autoSave)
        preview?.let { runCatching { wm.removeView(it) } }
        preview = null
    }

    /** Save the pending screenshot to the gallery (also used for auto-save). */
    private fun commitPreview() {
        val bmp = pendingBitmap ?: return
        pendingBitmap = null
        removePreview()
        io.execute {
            val ok = Storage.saveToGallery(applicationContext, bmp) != null
            handler.post { toast(if (ok) R.string.saved else R.string.capture_failed) }
        }
    }

    private fun discardPreview() {
        pendingBitmap = null
        removePreview()
        toast(R.string.discarded)
    }

    /** Hand the bitmap to CropActivity through the app's private cache directory. */
    private fun startCrop() {
        val bmp = pendingBitmap ?: return
        pendingBitmap = null
        removePreview()
        io.execute {
            File(cacheDir, "pending.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            handler.post {
                // Allowed from the background because the app holds SYSTEM_ALERT_WINDOW.
                startActivity(Intent(this, CropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, CaptureService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_capture), action(ACTION_CAPTURE, 1)) // shade trigger
            .addAction(0, getString(R.string.notif_stop), action(ACTION_STOP, 2))
            .build()
    }

    // ---------------------------------------------------------------- teardown / helpers

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        removePreview()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        latestImage?.close(); latestImage = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.let { projection = null; it.stop() }
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
}
