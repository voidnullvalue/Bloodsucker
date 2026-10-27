package com.bloodsucker.home.mqtt

import kotlinx.coroutines.*
import android.util.Log
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

class MqttClient(
    private val scope: CoroutineScope,
    private val onState: (Boolean, String?) -> Unit,
    private val onMessage: (String, ByteArray, Boolean) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        // `ble/_inventory` is a retained snapshot that is just over 1 MiB. Keep
        // enough room for useful inventory messages, while still bounding memory.
        internal const val MAX_INBOUND_PACKET_BYTES = 8 * 1024 * 1024
        private const val DISCARD_BUFFER_BYTES = 16 * 1024
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var running = false
    private var worker: Job? = null
    private val packetId = AtomicInteger(1)
    // Guards packet writes, output replacement and close/retire of a connection.
    private val writeLock = Any()

    private class ConnectionReplacedException : IOException("MQTT connection was replaced before publish")

    fun connect(uriText: String, clientId: String) {
        disconnect(); running = true
        worker = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive && running) {
                var connectionOutput: DataOutputStream? = null
                try {
                    onState(false, null)
                    val uri = URI(uriText); val port = if (uri.port > 0) uri.port else if (uri.scheme == "ssl" || uri.scheme == "tls") 8883 else 1883
                    val s = if (uri.scheme in setOf("ssl", "tls")) SSLSocketFactory.getDefault().createSocket(uri.host, port) else Socket(uri.host, port)
                    s.soTimeout = 15_000
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    connectionOutput = out
                    installConnection(s, out)
                    sendConnect(out, clientId)
                    val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val connType = input.readUnsignedByte(); val connLen = readRemaining(input)
                    val conn = ByteArray(connLen); input.readFully(conn)
                    if (connType shr 4 != 2 || conn.size < 2 || conn[1].toInt() != 0) throw IOException("Broker rejected connection")
                    onState(true, null); subscribe(out, "#"); backoff = 1_000L
                    while (isActive && running) {
                        try { readPacket(input) }
                        catch (_: SocketTimeoutException) { writePacket(out, 0xC0, byteArrayOf()) }
                    }
                } catch (e: Exception) {
                    val failed = connectionOutput
                    if (failed != null) {
                        val wasCurrent = isCurrent(failed)
                        closeConnection(failed)
                        if (running && wasCurrent) onState(false, e.message ?: "Connection lost")
                    } else if (running) {
                        onState(false, e.message ?: "Connection lost")
                    }
                    delay(backoff + Random.nextLong(0, 500)); backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    fun disconnect() { running = false; worker?.cancel(); worker = null; closeSocket(); onState(false, null) }

    fun publish(topic: String, payload: String): Boolean = publishWithCompletion(topic, payload, null)
    fun publish(topic: String, payload: String, onComplete: (Boolean) -> Unit): Boolean =
        publishWithCompletion(topic, payload, onComplete)

    /**
     * Enqueues a QoS 0 publish bound to the output stream that was current when
     * the command was accepted. Completion runs later on IO only after the full
     * packet has been written and flushed to that same local socket. For QoS 0
     * that local flush is the strongest available success boundary; there is no
     * broker acknowledgment. If the connection was replaced while queued, the
     * publish is dropped rather than written to the new socket. A write/flush
     * failure retires only the failed connection and reports false.
     */
    fun publishWithCompletion(topic: String, payload: String, onComplete: ((Boolean) -> Unit)?): Boolean {
        val capturedOutput = output ?: run {
            Log.w("BloodsuckerMqtt", "publish rejected: no active output stream")
            return false
        }
        scope.launch(ioDispatcher) {
            val result = runCatching {
                val topicBytes = topic.toByteArray()
                val body = ByteArrayOutputStream().also { b -> writeUtf(b, topicBytes); b.write(payload.toByteArray()) }.toByteArray()
                writePacket(capturedOutput, 0x30, body)
            }
            result.onFailure { failure ->
                if (failure is ConnectionReplacedException) {
                    Log.w("BloodsuckerMqtt", "publish dropped: connection replaced before write")
                } else {
                    Log.w("BloodsuckerMqtt", "publish failed: ${failure.javaClass.simpleName}: ${failure.message}")
                    val failedCurrentConnection = isCurrent(capturedOutput)
                    closeConnection(capturedOutput)
                    if (running && failedCurrentConnection) onState(false, failure.message ?: "Publish failed")
                }
            }
            onComplete?.invoke(result.isSuccess)
        }
        return true
    }

    private fun sendConnect(out: DataOutputStream, clientId: String) {
        val body = ByteArrayOutputStream()
        writeUtf(body, "MQTT".toByteArray()); body.write(4); body.write(2); body.write(0); body.write(30)
        writeUtf(body, clientId.toByteArray()); writePacket(out, 0x10, body.toByteArray())
    }
    private fun subscribe(out: DataOutputStream, topic: String) {
        val id = nextId(); val body = ByteArrayOutputStream()
        body.write(id shr 8); body.write(id); writeUtf(body, topic.toByteArray()); body.write(0)
        writePacket(out, 0x82, body.toByteArray())
    }
    private fun readPacket(input: DataInputStream) {
        val header = input.readUnsignedByte(); val len = readRemaining(input)
        if (len > MAX_INBOUND_PACKET_BYTES) {
            discardPacket(input, len)
            Log.w("BloodsuckerMqtt", "discarded inbound MQTT packet of $len bytes")
            return
        }
        val body = ByteArray(len); input.readFully(body)
        when (header shr 4) {
            3 -> {
                if (body.size < 2) return
                val topicLen = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                if (topicLen > body.size - 2) return
                val topic = body.copyOfRange(2, 2 + topicLen).toString(Charsets.UTF_8)
                var start = 2 + topicLen
                if ((header shr 1) and 3 > 0) start += 2
                if (start <= body.size) onMessage(topic, body.copyOfRange(start, body.size), header and 1 != 0)
            }
            13 -> Unit
        }
    }
    private fun discardPacket(input: DataInputStream, length: Int) {
        val buffer = ByteArray(minOf(length, DISCARD_BUFFER_BYTES))
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size)
            input.readFully(buffer, 0, count)
            remaining -= count
        }
    }
    private fun writePacket(out: DataOutputStream, header: Int, body: ByteArray) {
        synchronized(writeLock) {
            if (output !== out) throw ConnectionReplacedException()
            writePacketTo(out, header, body)
        }
    }
    private fun writePacketTo(out: DataOutputStream, header: Int, body: ByteArray) {
        out.write(header); var n = body.size
        do { var digit = n % 128; n /= 128; if (n > 0) digit = digit or 128; out.write(digit) } while (n > 0)
        out.write(body); out.flush()
    }
    private fun readRemaining(input: DataInputStream): Int { var multiplier = 1; var value = 0; var digit: Int; do { digit = input.readUnsignedByte(); value += (digit and 127) * multiplier; multiplier *= 128; if (multiplier > 128 * 128 * 128 * 128) throw IOException("Malformed MQTT length") } while (digit and 128 != 0); return value }
    private fun writeUtf(out: OutputStream, bytes: ByteArray) { out.write(bytes.size shr 8); out.write(bytes.size); out.write(bytes) }
    private fun nextId() = packetId.getAndUpdate { if (it >= 65535) 1 else it + 1 }
    private fun installConnection(s: Socket, out: DataOutputStream) {
        synchronized(writeLock) { socket = s; output = out }
    }
    private fun isCurrent(out: DataOutputStream): Boolean = synchronized(writeLock) { output === out }
    private fun closeConnection(out: DataOutputStream) {
        val failedSocket = synchronized(writeLock) {
            if (output !== out) return
            val current = socket
            socket = null
            output = null
            current
        }
        runCatching { failedSocket?.close() }
    }
    private fun closeSocket() {
        val current = synchronized(writeLock) {
            val s = socket
            socket = null
            output = null
            s
        }
        runCatching { current?.close() }
    }
}
