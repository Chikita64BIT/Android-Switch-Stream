package br.com.streamswitch

import kotlin.math.abs

/** Touch and key commands from the Switch page, shared by the HTTP and WebSocket servers. */
object RemoteInput {
    val ALLOWED_KEY_CODES = setOf(4, 19, 20, 21, 22, 23, 66, 96, 97, 99, 100, 102, 103)

    private val fallbackTouches = HashMap<Int, FallbackTouch>()

    private class FallbackTouch(val startX: Int, val startY: Int, val startedAt: Long) {
        var lastX = startX
        var lastY = startY
    }

    private class TouchEvent(val type: Char, val id: Int, val x: Int, val y: Int)

    /**
     * [raw] is "d.<id>.<x>.<y>_m.<id>.<x>.<y>_u.<id>" with x/y as 0..10000 of the picture.
     * Returns "multi", "basic" or "off".
     */
    fun touch(raw: String): String {
        if (!SharedState.adbConnected) return "off"
        val width = SharedState.deviceWidth
        val height = SharedState.deviceHeight
        if (width <= 0 || height <= 0) return "off"

        val events = raw.split('_').mapNotNull { item ->
            val parts = item.split('.')
            val type = parts.getOrNull(0)?.singleOrNull() ?: return@mapNotNull null
            val id = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..8 } ?: return@mapNotNull null
            when (type) {
                'd', 'm' -> {
                    val x = normalizedToPixel(parts.getOrNull(2), width) ?: return@mapNotNull null
                    val y = normalizedToPixel(parts.getOrNull(3), height) ?: return@mapNotNull null
                    TouchEvent(type, id, x, y)
                }
                'u' -> TouchEvent(type, id, 0, 0)
                else -> null
            }
        }
        if (events.isEmpty()) return if (TouchBridge.isReady) "multi" else "basic"
        val lines = StringBuilder()
        for (event in events) {
            if (event.type == 'u') {
                lines.append("u ").append(event.id).append('\n')
            } else {
                lines.append(event.type).append(' ').append(event.id).append(' ')
                    .append(event.x).append(' ').append(event.y).append('\n')
            }
        }
        if (TouchBridge.send(lines.toString())) return "multi"
        handleFallbackTouch(events)
        return "basic"
    }

    fun key(code: Int): Boolean = code in ALLOWED_KEY_CODES && WirelessAdbController.key(code)

    /** Without the injector, gestures become `input tap` or `input swipe` when the finger lifts. */
    private fun handleFallbackTouch(events: List<TouchEvent>) = synchronized(fallbackTouches) {
        for (event in events) {
            when (event.type) {
                'd' -> fallbackTouches[event.id] = FallbackTouch(event.x, event.y, System.currentTimeMillis())
                'm' -> fallbackTouches[event.id]?.apply {
                    lastX = event.x
                    lastY = event.y
                }
                'u' -> fallbackTouches.remove(event.id)?.let { touch ->
                    val duration = System.currentTimeMillis() - touch.startedAt
                    val moved = abs(touch.lastX - touch.startX) + abs(touch.lastY - touch.startY)
                    if (moved < 24 && duration < 350) {
                        WirelessAdbController.tap(touch.startX, touch.startY)
                    } else {
                        WirelessAdbController.swipe(touch.startX, touch.startY, touch.lastX, touch.lastY, duration)
                    }
                }
            }
        }
    }

    private fun normalizedToPixel(value: String?, size: Int): Int? {
        val normalized = value?.toIntOrNull() ?: return null
        return (normalized.coerceIn(0, 10_000).toLong() * size / 10_000).toInt().coerceIn(0, size - 1)
    }
}
