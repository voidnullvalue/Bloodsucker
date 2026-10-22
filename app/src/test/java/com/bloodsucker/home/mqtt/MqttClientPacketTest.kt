package com.bloodsucker.home.mqtt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
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
