package com.bloodsucker.home.data

import org.junit.Assert.*
import org.junit.Test

class KasaMqttTest {
    @Test fun topicUsesNormalizedStableKey() {
        assertEquals("kasa/3c64cf562bf6", kasaMqttTopic("kasa:3C64CF562BF6"))
    }

    @Test fun hsvBoundariesAndPayloadShapeAreValidated() {
        assertEquals("[0,0,0]", kasaHsvPayload(0, 0, 0))
        assertEquals("[360,100,100]", kasaHsvPayload(360, 100, 100))
        assertNull(kasaHsvPayload(-1, 0, 0))
        assertNull(kasaHsvPayload(361, 0, 0))
        assertNull(kasaHsvPayload(0, -1, 0))
        assertNull(kasaHsvPayload(0, 101, 0))
        assertNull(kasaHsvPayload(0, 0, -1))
        assertNull(kasaHsvPayload(0, 0, 101))
    }

    @Test fun presetBoundariesAreValidated() {
        assertEquals("Light preset 1", kasaPresetPayload(1))
        assertEquals("Light preset 4", kasaPresetPayload(4))
        assertNull(kasaPresetPayload(0))
        assertNull(kasaPresetPayload(5))
    }

    @Test fun brightnessAndKelvinBoundariesAreValidated() {
        assertEquals("0", kasaBrightnessPayload(0))
        assertEquals("100", kasaBrightnessPayload(100))
        assertNull(kasaBrightnessPayload(-1))
        assertNull(kasaBrightnessPayload(101))
        assertEquals("2500", kasaColorTemperaturePayload(2500))
        assertEquals("6500", kasaColorTemperaturePayload(6500))
        assertNull(kasaColorTemperaturePayload(2499))
        assertNull(kasaColorTemperaturePayload(6501))
    }
}
