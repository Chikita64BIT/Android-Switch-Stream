package br.com.streamswitch

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Runs as the ADB shell user through app_process, which is allowed to inject input.
 * This gives the stream real multi-touch (joystick + camera + buttons at the same time),
 * which `input tap` cannot do.
 *
 * Protocol (one command per line, after the token line):
 *   d <id> <x> <y>   pointer down
 *   m <id> <x> <y>   pointer move
 *   u <id>           pointer up
 *   c                cancel every pointer
 *   k <keycode>      key press
 */
object TouchInjector {
    private const val MAX_POINTERS = 10

    private lateinit var injectMethod: Method
    private lateinit var inputManager: Any

    private val ids = IntArray(MAX_POINTERS)
    private val xs = FloatArray(MAX_POINTERS)
    private val ys = FloatArray(MAX_POINTERS)
    private var count = 0
    private var downTime = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        val token = args.getOrNull(1) ?: return
        loadInputManager()
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { server ->
            println("ready")
            while (true) {
                try {
                    server.accept().use { socket ->
                        socket.tcpNoDelay = true
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        if (reader.readLine() != token) return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            handle(line)
                        }
                    }
                } catch (_: Exception) {
                }
                cancelAll()
            }
        }
    }

    private fun loadInputManager() {
        val instance = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance").invoke(null)
        } catch (_: Exception) {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
        }
        inputManager = instance!!
        injectMethod = instance.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }

    private fun inject(event: InputEvent) {
        try {
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectMethod.invoke(inputManager, event, 0)
        } catch (_: Exception) {
        }
    }

    private fun handle(line: String) {
        val parts = line.trim().split(' ')
        when (parts.getOrNull(0)) {
            "d" -> {
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                val x = parts.getOrNull(2)?.toFloatOrNull() ?: return
                val y = parts.getOrNull(3)?.toFloatOrNull() ?: return
                down(id, x, y)
            }
            "m" -> {
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                val x = parts.getOrNull(2)?.toFloatOrNull() ?: return
                val y = parts.getOrNull(3)?.toFloatOrNull() ?: return
                move(id, x, y)
            }
            "u" -> up(parts.getOrNull(1)?.toIntOrNull() ?: return)
            "c" -> cancelAll()
            "k" -> key(parts.getOrNull(1)?.toIntOrNull() ?: return)
        }
    }

    private fun indexOf(id: Int): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (indexOf(id) >= 0) {
            move(id, x, y)
            return
        }
        if (count >= MAX_POINTERS) return
        ids[count] = id
        xs[count] = x
        ys[count] = y
        count++
        val action = if (count == 1) {
            downTime = SystemClock.uptimeMillis()
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        sendMotion(action)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val index = indexOf(id)
        if (index < 0) return
        xs[index] = x
        ys[index] = y
        sendMotion(MotionEvent.ACTION_MOVE)
    }

    private fun up(id: Int) {
        val index = indexOf(id)
        if (index < 0) return
        val action = if (count == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        sendMotion(action)
        for (i in index until count - 1) {
            ids[i] = ids[i + 1]
            xs[i] = xs[i + 1]
            ys[i] = ys[i + 1]
        }
        count--
    }

    private fun cancelAll() {
        if (count == 0) return
        sendMotion(MotionEvent.ACTION_CANCEL)
        count = 0
    }

    private fun sendMotion(action: Int) {
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 0.05f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        inject(event)
        event.recycle()
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(
                KeyEvent(
                    now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD
                )
            )
        }
    }
}
