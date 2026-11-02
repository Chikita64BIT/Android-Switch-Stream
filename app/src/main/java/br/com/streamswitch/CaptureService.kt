package br.com.streamswitch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_stream"
        private const val NOTIFICATION_ID = 761
        private const val PORT = 8765
        private const val SOCKET_PORT = 8767
        private const val ENCODERS = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var server: LocalControlServer? = null
    private var socketServer: StreamSocketServer? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var densityDpi = 0
    private var captureSequence = 0L

    // JPEG encoding is the slowest step, so frames are encoded on several cores at once.
    private val encoderPool = Executors.newFixedThreadPool(ENCODERS) { task ->
        Thread(task, "switch-mirror-jpeg").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    private val busyEncoders = AtomicInteger()
    private val freeBitmaps = ArrayDeque<Bitmap>()
    private val jpegBuffers = ThreadLocal.withInitial { ByteArrayOutputStream(256 * 1024) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // Games like Wuthering Waves rotate to landscape after the stream starts.
            if (displayId == android.view.Display.DEFAULT_DISPLAY) configureOutput()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            startCapture(resultCode, resultData)
            startLocalServer()
        } catch (error: Exception) {
            SharedState.pageUrl = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        frameThread = HandlerThread("switch-mirror-frames").also { it.start() }
        val handler = Handler(frameThread!!.looper)
        frameHandler = handler

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        configureOutput()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        SharedState.onQualityChanged = { handler.post { configureOutput() } }
    }

    /** Creates or resizes the capture to match the current rotation and quality preset. */
    private fun configureOutput() {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val sourceWidth = metrics.widthPixels
        val sourceHeight = metrics.heightPixels
        SharedState.deviceWidth = sourceWidth
        SharedState.deviceHeight = sourceHeight

        val quality = SharedState.quality
        val longestSide = max(sourceWidth, sourceHeight).toFloat()
        val scale = min(1f, quality.maxSide / longestSide)
        // Even sizes avoid stride padding on many GPUs.
        val width = max(2, (sourceWidth * scale).toInt() and 1.inv())
        val height = max(2, (sourceHeight * scale).toInt() and 1.inv())
        if (width == outputWidth && height == outputHeight && virtualDisplay != null) return
        outputWidth = width
        outputHeight = height
        densityDpi = metrics.densityDpi

        val handler = frameHandler ?: return
        val oldReader = imageReader
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ pullFrame(it) }, handler)
        imageReader = reader

        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "AndroidSwitchMirror",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } else {
            display.resize(width, height, densityDpi)
            display.surface = reader.surface
        }
        oldReader?.close()
    }

    /** Copies the newest captured image and hands it to a free encoder. Runs on the frame thread. */
    private fun pullFrame(reader: ImageReader?) {
        if (reader == null || reader !== imageReader) return
        if (busyEncoders.get() >= ENCODERS) return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        val width = image.width
        val height = image.height
        val bitmap: Bitmap
        try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            bitmap = takeBitmap(paddedWidth, height)
            plane.buffer.rewind()
            bitmap.copyPixelsFromBuffer(plane.buffer)
        } catch (_: Exception) {
            return
        } finally {
            image.close()
        }

        val sequence = ++captureSequence
        val quality = SharedState.quality.jpegQuality
        busyEncoders.incrementAndGet()
        try {
            encoderPool.execute {
                try {
                    val frame = if (bitmap.width == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val buffer = jpegBuffers.get()!!
                    buffer.reset()
                    frame.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                    if (frame !== bitmap) frame.recycle()
                    SharedState.publishFrame(buffer.toByteArray(), sequence, width, height)
                } catch (_: Exception) {
                } finally {
                    returnBitmap(bitmap)
                    busyEncoders.decrementAndGet()
                    // A frame may have arrived while every encoder was busy.
                    frameHandler?.post { pullFrame(imageReader) }
                }
            }
        } catch (_: Exception) {
            busyEncoders.decrementAndGet()
            returnBitmap(bitmap)
        }
    }

    private fun takeBitmap(width: Int, height: Int): Bitmap {
        synchronized(freeBitmaps) {
            while (freeBitmaps.isNotEmpty()) {
                val bitmap = freeBitmaps.removeFirst()
                if (bitmap.width == width && bitmap.height == height) return bitmap
                bitmap.recycle()
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun returnBitmap(bitmap: Bitmap) {
        synchronized(freeBitmaps) {
            if (freeBitmaps.size < ENCODERS + 1) freeBitmaps.addLast(bitmap) else bitmap.recycle()
        }
    }

    private fun startLocalServer() {
        val ip = localIpv4Address()
        server = LocalControlServer(PORT, java.io.File(filesDir, "switch-page.json")).also {
            it.start()
        }
        socketServer = StreamSocketServer(SOCKET_PORT).also { it.start() }
        SharedState.pageUrl = if (ip == null) {
            "IP Wi‑Fi não encontrada; liga o telemóvel à mesma rede do Switch."
        } else {
            "http://" + ip + ":" + PORT
        }
    }

    private fun localIpv4Address(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transmissão de tela",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Android → Switch ativo")
            .setContentText("A tela está sendo transmitida na rede local.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        SharedState.pageUrl = null
        SharedState.latestJpeg = null
        SharedState.onQualityChanged = null
        try {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        server?.stop()
        server = null
        socketServer?.stop()
        socketServer = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        encoderPool.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
