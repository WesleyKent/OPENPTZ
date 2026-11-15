package com.opencapture.openpocketcine.visca

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sony-compatible VISCA over IP transport (UDP, default port 52381). */
class ViscaOverIpClient(
    rawHost: String,
    val port: Int = DEFAULT_PORT,
) : Closeable {
    val host: String = normalizeHost(rawHost)
    private val endpoint = InetSocketAddress(InetAddress.getByName(host), port.coerceIn(1, 65_535))
    private val sequence = AtomicLong(1)
    private val lock = Mutex()
    private val socket = DatagramSocket().apply {
        connect(endpoint)
        soTimeout = REPLY_TIMEOUT_MS
    }

    /** Send a command and require the matching ACK/completion or an inquiry reply. */
    suspend fun command(payload: ByteArray): ViscaReply =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val seq = nextSequence()
                sendPacket(ViscaIpPacketCodec.command(payload, seq))
                awaitReply(seq)
            }
        }

    /** Send a continuous-drive update without stalling the live joystick on a reply. */
    suspend fun realtime(payload: ByteArray) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // Continuous-drive replies are deliberately not awaited, but
                // they still arrive on this socket. Drain prior ACK/completion
                // packets so a long joystick hold cannot fill the receive queue.
                drainAvailableReplies()
                sendPacket(ViscaIpPacketCodec.command(payload, nextSequence()))
            }
        }

    /** Reset the camera-side sequence counter after a controller reconnect. */
    suspend fun resetSequence() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                sendPacket(ViscaIpPacketCodec.sequenceReset())
                sequence.set(1)
            }
        }

    private fun nextSequence(): Long = sequence.getAndUpdate { (it + 1) and UINT32_MASK }

    private fun sendPacket(bytes: ByteArray) {
        check(!socket.isClosed) { "VISCA connection is closed" }
        socket.send(DatagramPacket(bytes, bytes.size, endpoint))
    }

    private fun awaitReply(expectedSequence: Long): ViscaReply {
        val receive = ByteArray(64)
        var sawAck = false
        while (true) {
            val packet = DatagramPacket(receive, receive.size)
            try {
                socket.receive(packet)
            } catch (timeout: SocketTimeoutException) {
                val suffix = if (sawAck) " after ACK" else ""
                throw IOException("VISCA ${host}:${port} timed out$suffix", timeout)
            }
            val decoded = ViscaIpPacketCodec.decode(receive.copyOf(packet.length)) ?: continue
            if (decoded.sequence != expectedSequence) continue
            val reply = ViscaReply.from(decoded.payload)
            when (reply) {
                is ViscaReply.Ack -> sawAck = true
                is ViscaReply.Error -> throw IOException(reply.message)
                else -> return reply
            }
        }
    }

    private fun drainAvailableReplies() {
        val previousTimeout = socket.soTimeout
        try {
            socket.soTimeout = DRAIN_TIMEOUT_MS
            val receive = ByteArray(64)
            while (true) {
                val packet = DatagramPacket(receive, receive.size)
                socket.receive(packet)
            }
        } catch (_: SocketTimeoutException) {
            // Queue drained.
        } finally {
            if (!socket.isClosed) socket.soTimeout = previousTimeout
        }
    }

    override fun close() {
        if (socket.isClosed) return
        // A controller can disappear while a continuous drive is active. Send
        // best-effort stop packets synchronously before closing so a PTZ head
        // cannot be left moving by coroutine cancellation or screen teardown.
        runCatching {
            sendPacket(ViscaIpPacketCodec.command(ViscaCommands.panTiltStop(), nextSequence()))
            sendPacket(ViscaIpPacketCodec.command(ViscaCommands.zoomStop(), nextSequence()))
        }
        socket.close()
    }

    companion object {
        const val DEFAULT_PORT = 52_381
        private const val REPLY_TIMEOUT_MS = 850
        private const val DRAIN_TIMEOUT_MS = 1
        private const val UINT32_MASK = 0xFFFF_FFFFL

        fun normalizeHost(raw: String): String =
            raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
    }
}

sealed interface ViscaReply {
    data class Ack(val socket: Int) : ViscaReply
    data class Complete(val socket: Int, val payload: ByteArray) : ViscaReply
    data class Inquiry(val payload: ByteArray) : ViscaReply
    data class Error(val code: Int, val socket: Int, val message: String) : ViscaReply

    companion object {
        fun from(payload: ByteArray): ViscaReply {
            if (payload.size < 3 || payload.first().toInt() and 0xF0 != 0x90 || payload.last() != 0xFF.toByte()) {
                return Inquiry(payload)
            }
            val status = payload[1].toInt() and 0xFF
            val kind = status and 0xF0
            val socket = status and 0x0F
            return when (kind) {
                0x40 -> Ack(socket)
                0x50 -> if (payload.size == 3) Complete(socket, payload) else Inquiry(payload)
                0x60 -> {
                    val code = payload.getOrNull(2)?.toInt()?.and(0xFF) ?: -1
                    Error(code, socket, "VISCA error ${errorLabel(code)} (0x${code.toString(16).padStart(2, '0')})")
                }
                else -> Inquiry(payload)
            }
        }

        private fun errorLabel(code: Int): String =
            when (code) {
                0x01 -> "message length"
                0x02 -> "syntax"
                0x03 -> "command buffer full"
                0x04 -> "command canceled"
                0x05 -> "no socket"
                0x41 -> "command not executable"
                else -> "camera reply"
            }
    }
}

/** Eight-byte VISCA-over-IP envelope. Values are encoded in network byte order. */
object ViscaIpPacketCodec {
    const val COMMAND_TYPE = 0x0100
    const val REPLY_TYPE = 0x0111
    const val DEVICE_SETTING_TYPE = 0x0200

    data class Packet(val type: Int, val sequence: Long, val payload: ByteArray)

    fun command(payload: ByteArray, sequence: Long): ByteArray = encode(COMMAND_TYPE, payload, sequence)

    fun sequenceReset(): ByteArray = encode(DEVICE_SETTING_TYPE, byteArrayOf(0x01), 0)

    fun encode(type: Int, payload: ByteArray, sequence: Long): ByteArray {
        require(payload.size in 1..16) { "VISCA payload must contain 1 to 16 bytes" }
        return ByteArray(8 + payload.size).also { packet ->
            packet[0] = (type ushr 8).toByte()
            packet[1] = type.toByte()
            packet[2] = (payload.size ushr 8).toByte()
            packet[3] = payload.size.toByte()
            packet[4] = (sequence ushr 24).toByte()
            packet[5] = (sequence ushr 16).toByte()
            packet[6] = (sequence ushr 8).toByte()
            packet[7] = sequence.toByte()
            payload.copyInto(packet, destinationOffset = 8)
        }
    }

    fun decode(packet: ByteArray): Packet? {
        if (packet.size < 9) return null
        val type = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val length = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (length !in 1..16 || packet.size < 8 + length) return null
        val sequence =
            ((packet[4].toLong() and 0xFF) shl 24) or
                ((packet[5].toLong() and 0xFF) shl 16) or
                ((packet[6].toLong() and 0xFF) shl 8) or
                (packet[7].toLong() and 0xFF)
        return Packet(type, sequence, packet.copyOfRange(8, 8 + length))
    }
}
