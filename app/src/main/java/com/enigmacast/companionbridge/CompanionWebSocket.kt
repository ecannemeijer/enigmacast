package com.enigmacast.companionbridge

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimale RFC 6455-server: handshake, tekstframes, ping/pong en close. Geen dependency,
 * omdat `nanohttpd-websocket` niet in de offline cache zit.
 */
object CompanionWebSocket {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val OP_CONTINUATION = 0x0
    private const val OP_TEXT = 0x1
    private const val OP_CLOSE = 0x8
    private const val OP_PING = 0x9
    private const val OP_PONG = 0xA

    data class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean)

    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) throw EOFException("onvolledige frame")
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length > CompanionProtocol.MAX_FRAME_BYTES) throw IllegalArgumentException("frame te groot")
        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length.toInt()).also { readFully(input, it) }
        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        return Frame(opcode, payload, fin)
    }

    fun writeText(output: OutputStream, text: String) = write(output, OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun writePong(output: OutputStream, payload: ByteArray) = write(output, OP_PONG, payload)

    fun writePing(output: OutputStream, payload: ByteArray = ByteArray(0)) = write(output, OP_PING, payload)

    fun writeClose(output: OutputStream, code: Int = 1000) {
        val payload = byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte())
        write(output, OP_CLOSE, payload)
    }

    fun opcodeText() = OP_TEXT

    fun opcodeClose() = OP_CLOSE

    fun opcodePing() = OP_PING

    fun opcodePong() = OP_PONG

    fun opcodeContinuation() = OP_CONTINUATION

    private fun write(output: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            output.write(0x80 or (opcode and 0x0F))
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65536 -> {
                    output.write(126)
                    output.write((payload.size shr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    var size = payload.size.toLong()
                    val bytes = ByteArray(8)
                    for (index in 7 downTo 0) {
                        bytes[index] = (size and 0xFF).toByte()
                        size = size shr 8
                    }
                    output.write(bytes)
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("onverwacht einde")
        return value
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("onverwacht einde")
            offset += read
        }
    }
}
