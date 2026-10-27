package com.bloodsucker.home.mqtt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttClientPacketTest {
    @Test fun retainedInventorySizedPacketDoesNotExceedInboundLimit() {
        // The live retained ble/_inventory snapshot is 1,051,781 bytes.
        val body = ByteArray(1_051_781)
        val input = DataInputStream(ByteArrayInputStream(packet(0x30, body) + byteArrayOf(0xD0.toByte(), 0)))
        val client = MqttClient(CoroutineScope(Dispatchers.Unconfined), { _: Boolean, _: String? -> }, { _, _, _ -> })

        val readPacket = MqttClient::class.java.getDeclaredMethod("readPacket", DataInputStream::class.java)
            .apply { isAccessible = true }
        readPacket.invoke(client, input)

        assertEquals(0xD0, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
    }

    @Test fun publishCompletionReportsWriteFailure() {
        val client = MqttClient(CoroutineScope(Dispatchers.IO), { _, _ -> }, { _, _, _ -> })
        val output = DataOutputStream(object : OutputStream() {
            override fun write(b: Int) { throw IOException("socket write failed") }
        })
        MqttClient::class.java.getDeclaredField("output").apply { isAccessible = true }.set(client, output)

        val latch = CountDownLatch(1)
        var completed: Boolean? = null
        assertTrue(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") {
            completed = it
            latch.countDown()
        })
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, completed)
    }

    @Test fun publishCompletionReportsFlushFailure() {
        val client = MqttClient(CoroutineScope(Dispatchers.IO), { _, _ -> }, { _, _, _ -> })
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(object : OutputStream() {
            override fun write(b: Int) = bytes.write(b)
            override fun flush() { throw IOException("socket flush failed") }
        })
        MqttClient::class.java.getDeclaredField("output").apply { isAccessible = true }.set(client, output)

        val latch = CountDownLatch(1)
        var completed: Boolean? = null
        assertTrue(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") {
            completed = it
            latch.countDown()
        })
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, completed)
        assertFalse(bytes.size() == 0)
    }

    @Test fun queuedPublishRejectsReplacementOutputInsteadOfWritingToNewSocket() {
        val dispatcher = ManualDispatcher()
        val client = MqttClient(CoroutineScope(Dispatchers.Unconfined), { _, _ -> }, { _, _, _ -> }, dispatcher)
        val oldBytes = ByteArrayOutputStream()
        client.setOutputForTest(DataOutputStream(oldBytes))

        var completed: Boolean? = null
        assertTrue(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") { completed = it })
        assertNull(completed)

        val newBytes = ByteArrayOutputStream()
        client.setOutputForTest(DataOutputStream(newBytes))
        dispatcher.runAll()

        assertEquals(false, completed)
        assertEquals(0, oldBytes.size())
        assertEquals(0, newBytes.size())
    }

    @Test fun publishFailureRetiresOnlyTheFailedConnection() {
        val dispatcher = ManualDispatcher()
        val client = MqttClient(CoroutineScope(Dispatchers.Unconfined), { _, _ -> }, { _, _, _ -> }, dispatcher)
        val newBytes = ByteArrayOutputStream()
        val newOutput = DataOutputStream(newBytes)
        val oldOutput = DataOutputStream(object : OutputStream() {
            override fun write(b: Int) {
                client.setOutputForTest(newOutput)
                throw IOException("socket write failed")
            }
        })
        client.setOutputForTest(oldOutput)

        var completed: Boolean? = null
        assertTrue(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") { completed = it })
        dispatcher.runAll()

        assertEquals(false, completed)
        assertSame(newOutput, client.outputForTest())
        assertEquals(0, newBytes.size())
    }

    @Test fun publishCompletionSucceedsAfterQueuedFlush() {
        val dispatcher = ManualDispatcher()
        val client = MqttClient(CoroutineScope(Dispatchers.Unconfined), { _, _ -> }, { _, _, _ -> }, dispatcher)
        val bytes = ByteArrayOutputStream()
        client.setOutputForTest(DataOutputStream(bytes))

        var completed: Boolean? = null
        assertTrue(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") { completed = it })
        assertNull(completed)
        dispatcher.runAll()

        assertEquals(true, completed)
        assertTrue(bytes.size() > 0)
    }

    @Test fun publishWithoutActiveOutputIsRejected() {
        val dispatcher = ManualDispatcher()
        val client = MqttClient(CoroutineScope(Dispatchers.Unconfined), { _, _ -> }, { _, _, _ -> }, dispatcher)
        client.setOutputForTest(null)

        var called = false
        assertFalse(client.publishWithCompletion("geo-lamp/light/command", """{"state":"ON"}""") { called = true })
        dispatcher.runAll()
        assertFalse(called)
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun MqttClient.setOutputForTest(out: DataOutputStream?) {
        MqttClient::class.java.getDeclaredField("output").apply { isAccessible = true }.set(this, out)
    }

    private fun MqttClient.outputForTest(): DataOutputStream? =
        MqttClient::class.java.getDeclaredField("output").apply { isAccessible = true }.get(this) as DataOutputStream?

    private fun packet(header: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(header)
        var remaining = body.size
        do {
            var digit = remaining % 128
            remaining /= 128
            if (remaining > 0) digit = digit or 128
            write(digit)
        } while (remaining > 0)
        write(body)
    }.toByteArray()
}
