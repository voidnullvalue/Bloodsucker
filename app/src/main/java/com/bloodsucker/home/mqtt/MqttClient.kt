package com.bloodsucker.home.mqtt

import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

class MqttClient(
    private val scope: CoroutineScope,
    private val onState: (Boolean, String?) -> Unit,
    private val onMessage: (String, ByteArray, Boolean) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var running = false
    private var worker: Job? = null
    private val packetId = AtomicInteger(1)

    fun connect(uriText: String, clientId: String) {
        disconnect(); running = true
        worker = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive && running) {
                try {
                    onState(false, null)
                    val uri = URI(uriText); val port = if (uri.port > 0) uri.port else if (uri.scheme == "ssl" || uri.scheme == "tls") 8883 else 1883
                    val s = if (uri.scheme in setOf("ssl", "tls")) SSLSocketFactory.getDefault().createSocket(uri.host, port) else Socket(uri.host, port)
                    s.soTimeout = 45_000; socket = s; output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    sendConnect(clientId)
                    val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val connType = input.readUnsignedByte(); val connLen = readRemaining(input)
                    val conn = ByteArray(connLen); input.readFully(conn)
                    if (connType shr 4 != 2 || conn.size < 2 || conn[1].toInt() != 0) throw IOException("Broker rejected connection")
                    onState(true, null); subscribe("#"); backoff = 1_000L
                    while (isActive && running) readPacket(input)
                } catch (e: Exception) {
                    closeSocket(); if (running) onState(false, e.message ?: "Connection lost")
                    delay(backoff + Random.nextLong(0, 500)); backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    fun disconnect() { running = false; worker?.cancel(); worker = null; closeSocket(); onState(false, null) }

    @Synchronized fun publish(topic: String, payload: String): Boolean = runCatching {
        if (output == null) return@runCatching false
        val topicBytes = topic.toByteArray(); val body = ByteArrayOutputStream().also { b -> writeUtf(b, topicBytes); b.write(payload.toByteArray()) }.toByteArray()
        writePacket(0x30, body)
        true
    }.getOrElse { false }

    private fun sendConnect(clientId: String) {
        val body = ByteArrayOutputStream()
        writeUtf(body, "MQTT".toByteArray()); body.write(4); body.write(2); body.write(0); body.write(30)
        writeUtf(body, clientId.toByteArray()); writePacket(0x10, body.toByteArray())
    }
    private fun subscribe(topic: String) {
        val id = nextId(); val body = ByteArrayOutputStream()
        body.write(id shr 8); body.write(id); writeUtf(body, topic.toByteArray()); body.write(0)
        writePacket(0x82, body.toByteArray())
    }
    private fun readPacket(input: DataInputStream) {
        val header = input.readUnsignedByte(); val len = readRemaining(input); if (len > 1_048_576) throw IOException("MQTT packet too large")
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
    @Synchronized private fun writePacket(header: Int, body: ByteArray) {
        val out = output ?: return
        out.write(header); var n = body.size
        do { var digit = n % 128; n /= 128; if (n > 0) digit = digit or 128; out.write(digit) } while (n > 0)
        out.write(body); out.flush()
    }
    private fun readRemaining(input: DataInputStream): Int { var multiplier = 1; var value = 0; var digit: Int; do { digit = input.readUnsignedByte(); value += (digit and 127) * multiplier; multiplier *= 128; if (multiplier > 128 * 128 * 128 * 128) throw IOException("Malformed MQTT length") } while (digit and 128 != 0); return value }
    private fun writeUtf(out: OutputStream, bytes: ByteArray) { out.write(bytes.size shr 8); out.write(bytes.size); out.write(bytes) }
    private fun nextId() = packetId.getAndUpdate { if (it >= 65535) 1 else it + 1 }
    private fun closeSocket() { runCatching { socket?.close() }; socket = null; output = null }
}
