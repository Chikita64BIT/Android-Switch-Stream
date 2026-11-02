package br.com.streamswitch

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Minimal WebSocket server for the Switch browser, modeled on Switch-PC-Stream:
 * frames go out as binary JPEG messages and the page acknowledges every frame it draws.
 * At most [MAX_IN_FLIGHT] frames wait on the Switch, so a slow decoder never builds a backlog
 * and always gets the newest picture. Touch and key commands come back on the same socket.
 */
class StreamSocketServer(private val port: Int) {
    companion object {
        private const val MAX_IN_FLIGHT = 2
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_TEXT = 64 * 1024
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = mutableSetOf<Socket>()

    fun start() {
        val server = ServerSocket(port)
        serverSocket = server
        running = true
        Thread({
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                synchronized(clients) { clients.add(socket) }
                Thread({ serve(socket) }, "switch-ws-client").apply { isDaemon = true }.start()
            }
        }, "switch-ws-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) { } }
            clients.clear()
        }
        synchronized(SharedState.frameLock) { SharedState.frameLock.notifyAll() }
    }

    private class Connection(val output: OutputStream) {
        @Volatile var acked = 0L
        @Volatile var open = true
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            if (!handshake(input, output)) return
            val connection = Connection(output)
            Thread({ readLoop(input, connection) }, "switch-ws-read").apply { isDaemon = true }.start()
            sendLoop(connection)
        } catch (_: Exception) {
        } finally {
            synchronized(clients) { clients.remove(socket) }
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun handshake(input: InputStream, output: OutputStream): Boolean {
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0 || headers.length > 8192) return false
            headers.append(next.toChar())
        }
        val key = headers.lines()
            .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?: return false
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()),
            Base64.NO_WRAP
        )
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        output.flush()
        return true
    }

    private fun sendLoop(connection: Connection) {
        var lastSequence = -1L
        var sent = 0L
        while (running && connection.open) {
            val frame: ByteArray
            synchronized(SharedState.frameLock) {
                while (running && connection.open &&
                    (SharedState.latestJpeg == null || SharedState.frameSequence <= lastSequence ||
                        sent - connection.acked >= MAX_IN_FLIGHT)
                ) {
                    SharedState.frameLock.wait(250)
                }
                if (!running || !connection.open) return
                lastSequence = SharedState.frameSequence
                frame = SharedState.latestJpeg!!
            }
            writeFrame(connection.output, 0x2, frame)
            sent++
            SharedState.deliveredFps.tick()
        }
    }

    private fun readLoop(input: InputStream, connection: Connection) {
        try {
            while (connection.open) {
                val first = input.read()
                if (first < 0) break
                val opcode = first and 0x0f
                val second = readByte(input)
                var length = (second and 0x7f).toLong()
                if (length == 126L) {
                    length = ((readByte(input) shl 8) or readByte(input)).toLong()
                } else if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or readByte(input).toLong() }
                }
                if (length > MAX_TEXT) break
                val mask = if (second and 0x80 != 0) ByteArray(4) { readByte(input).toByte() } else null
                val payload = ByteArray(length.toInt())
                var read = 0
                while (read < payload.size) {
                    val count = input.read(payload, read, payload.size - read)
                    if (count < 0) throw EOFException()
                    read += count
                }
                if (mask != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                }
                when (opcode) {
                    0x1 -> handleText(String(payload), connection)
                    0x8 -> break
                    0x9 -> writeFrame(connection.output, 0xA, payload)
                }
            }
        } catch (_: Exception) {
        } finally {
            connection.open = false
            synchronized(SharedState.frameLock) { SharedState.frameLock.notifyAll() }
        }
    }

    private fun handleText(message: String, connection: Connection) {
        val space = message.indexOf(' ')
        val command = if (space < 0) message else message.substring(0, space)
        val argument = if (space < 0) "" else message.substring(space + 1)
        when (command) {
            // "a <total frames drawn on this connection>"
            "a" -> argument.toLongOrNull()?.let { total ->
                synchronized(SharedState.frameLock) {
                    if (total > connection.acked) connection.acked = total
                    SharedState.frameLock.notifyAll()
                }
            }
            "t" -> RemoteInput.touch(argument)
            "k" -> argument.toIntOrNull()?.let { RemoteInput.key(it) }
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xffff -> {
                    output.write(126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xff)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() shr shift) and 0xff).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }
    }
}
