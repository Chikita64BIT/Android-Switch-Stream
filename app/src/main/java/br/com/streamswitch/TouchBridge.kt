package br.com.streamswitch

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Starts [TouchInjector] as the ADB shell user and keeps a loopback socket to it.
 * The token stops other apps on the phone from using the injector port.
 */
object TouchBridge {
    private const val PORT = 8766
    private const val PROCESS_NAME = "sstouch"
    private val lock = Any()

    @Volatile
    private var output: OutputStream? = null
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var generation = 0

    val isReady: Boolean get() = output != null

    fun start(context: Context, manager: AbsAdbConnectionManager) {
        stop()
        val myGeneration = synchronized(lock) { ++generation }
        val apk = context.applicationContext.applicationInfo.sourceDir
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        Thread({
            try {
                runShell(manager, "pkill -x $PROCESS_NAME")
                val command = "shell:CLASSPATH=$apk exec app_process / --nice-name=$PROCESS_NAME " +
                    "br.com.streamswitch.TouchInjector $PORT $token"
                manager.openStream(command).use { stream ->
                    val input = stream.openInputStream()
                    val buffer = ByteArray(256)
                    val seen = StringBuilder()
                    var connected = false
                    while (generation == myGeneration) {
                        val read = try {
                            input.read(buffer)
                        } catch (_: IOException) {
                            -1
                        }
                        if (read < 0) break
                        if (!connected) {
                            seen.append(String(buffer, 0, read))
                            if (seen.contains("ready")) {
                                connected = connect(token, myGeneration)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (generation == myGeneration) closeSocket()
            }
        }, "touch-bridge").apply { isDaemon = true }.start()
    }

    private fun runShell(manager: AbsAdbConnectionManager, command: String) {
        try {
            manager.openStream("shell:$command").use { stream ->
                val input = stream.openInputStream()
                val buffer = ByteArray(128)
                while (true) {
                    try {
                        if (input.read(buffer) < 0) break
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun connect(token: String, myGeneration: Int): Boolean {
        repeat(10) {
            try {
                val newSocket = Socket(InetAddress.getLoopbackAddress(), PORT).apply { tcpNoDelay = true }
                val stream = newSocket.getOutputStream()
                stream.write((token + "\n").toByteArray())
                stream.flush()
                synchronized(lock) {
                    if (generation != myGeneration) {
                        newSocket.close()
                        return false
                    }
                    socket = newSocket
                    output = stream
                }
                return true
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        return false
    }

    /** Sends injector commands, one per line. Returns false when the injector is not running. */
    fun send(lines: String): Boolean {
        synchronized(lock) {
            val stream = output ?: return false
            return try {
                stream.write(lines.toByteArray())
                stream.flush()
                true
            } catch (_: Exception) {
                closeSocketLocked()
                false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            closeSocketLocked()
        }
    }

    private fun closeSocket() = synchronized(lock) { closeSocketLocked() }

    private fun closeSocketLocked() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        output = null
    }
}
