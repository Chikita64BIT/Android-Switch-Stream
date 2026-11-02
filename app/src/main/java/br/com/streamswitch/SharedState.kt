package br.com.streamswitch

object SharedState {
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var frameSequence: Long = 0
    @Volatile var pageUrl: String? = null
    @Volatile var deviceWidth: Int = 0
    @Volatile var deviceHeight: Int = 0
    @Volatile var frameWidth: Int = 0
    @Volatile var frameHeight: Int = 0
    @Volatile var adbConnected: Boolean = false
    @Volatile var adbStatus: String = "ADB Wi-Fi desconectado"
    @Volatile var quality: StreamQuality = StreamQuality.MEDIUM
    @Volatile var onQualityChanged: (() -> Unit)? = null

    val frameLock = Object()
    private var lastCaptureSequence = 0L
    val encodedFps = FpsCounter()
    val deliveredFps = FpsCounter()

    /** Parallel encoders can finish out of order; older frames are dropped. */
    fun publishFrame(jpeg: ByteArray, captureSequence: Long, width: Int, height: Int) {
        synchronized(frameLock) {
            if (captureSequence <= lastCaptureSequence) return
            lastCaptureSequence = captureSequence
            latestJpeg = jpeg
            frameWidth = width
            frameHeight = height
            frameSequence++
            frameLock.notifyAll()
        }
        encodedFps.tick()
    }
}

class FpsCounter {
    private var windowStart = System.currentTimeMillis()
    private var count = 0
    @Volatile var value = 0
        private set

    @Synchronized
    fun tick() {
        count++
        roll()
    }

    @Synchronized
    fun current(): Int {
        roll()
        return value
    }

    private fun roll() {
        val now = System.currentTimeMillis()
        val elapsed = now - windowStart
        if (elapsed >= 1_000) {
            value = (count * 1_000 / elapsed).toInt()
            count = 0
            windowStart = now
        }
    }
}

enum class StreamQuality(val id: String, val maxSide: Int, val jpegQuality: Int) {
    // Switch WebKit decodes small, low-quality JPEGs fastest (see Switch-PC-Stream's Switch profile).
    LOW("baixa", 640, 35),
    MEDIUM("media", 924, 45),
    HIGH("alta", 1136, 55),
    MAX("max", 1280, 70);

    companion object {
        fun fromId(id: String?): StreamQuality? = entries.firstOrNull { it.id == id }
    }
}
