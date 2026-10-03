package com.bloodsucker.home.discovery

import com.bloodsucker.home.model.DeviceKind
import org.junit.Assert.*
import org.junit.Test

class TopicDiscoveryTest {
    @Test fun h5075DecodesTemperatureHumidityAndBattery() {
        // packed value 21.5 C and 45.6% RH = 215456 = 0x0349A0
        val json = """{"name":"GVH5075 H5075","mac":"A4:C1:38:11:22:33","manufacturerData":{"60552":[0,3,73,160,87]},"rssi":-62}"""
        val d = TopicDiscovery.parse("ble/kitchen/A4:C1:38:11:22:33", json.toByteArray()).device!!
        assertEquals("govee:A4C138112233", d.key)
        assertEquals("70.7°F", d.readings.first { it.label == "Temperature" }.value)
        assertEquals("45.6%", d.readings.first { it.label == "Humidity" }.value)
        assertEquals("87%", d.readings.first { it.label == "Battery" }.value)
    }

    @Test fun duplicateScannerTopicsProduceSameStableKey() {
        val json = """{"name":"Govee H5075","mac":"A4:C1:38:11:22:33","manufacturerData":{"60552":[0,3,73,160,87]}}"""
        val a = TopicDiscovery.parse("ble/A4_C1_38_11_22_33/state", json.toByteArray()).device
        val b = TopicDiscovery.parse("ble/scanner/A4:C1:38:11:22:33", json.toByteArray()).device
        assertEquals(a!!.key, b!!.key)
    }

    @Test fun unknownGoveeNeverFabricatesReadings() {
        val json = """{"name":"Govee H9999","mac":"AA:BB:CC:DD:EE:FF","manufacturerData":{"60552":[0,3,73,160,87]}}"""
        val d = TopicDiscovery.parse("ble/AA_BB_CC_DD_EE_FF/state", json.toByteArray()).device!!
        assertFalse(d.supported)
        assertTrue(d.readings.none { it.label == "Temperature" })
    }

    @Test fun matterInfrastructureIsRecognizedButHidden() {
        val r = TopicDiscovery.parse("matter/1/0/40/3", """{"value":"H7126"}""".toByteArray())
        assertTrue(r.recognized)
        assertNull(r.device)
    }

    @Test fun matterTemperatureConvertsToFahrenheit() {
        val d = TopicDiscovery.parse("matter/4/2/1026/0", """{"value":2250,"attribute":"Temperature"}""".toByteArray()).device!!
        assertEquals(DeviceKind.MATTER, d.kind)
        assertEquals("72.5°F", d.readings.single().value)
    }

    @Test fun secretsAndOversizedPayloadsAreIgnored() {
        assertFalse(TopicDiscovery.parse("house/password/state", "hello".toByteArray()).recognized)
        assertFalse(TopicDiscovery.parse("house/sensor/temp", ByteArray(65 * 1024)).recognized)
    }

    @Test fun weatherForecastIndicesAreDynamic() {
        val r = TopicDiscovery.parse("weather/wttr/forecast/8/max_temp_f", "81".toByteArray())
        assertEquals(8, r.forecast!!.index)
        assertEquals("81", r.forecast.high)
    }

    @Test fun wledStateExposesPrettyControlStateFields() {
        val xml = "<vs><ds>Kitchen glow</ds><fx>42</fx><fp>7</fp><ps>3</ps><sx>120</sx><ix>200</ix></vs>"
        val d = TopicDiscovery.parse("wled/abcdef/v", xml.toByteArray()).device!!
        assertEquals("Kitchen glow", d.name)
        assertEquals("42", d.readings.first { it.label == "Effect" }.value)
        assertEquals("7", d.readings.first { it.label == "Palette" }.value)
        assertEquals("3", d.readings.first { it.label == "Preset" }.value)
    }

    @Test fun kasaStateUsesStableIdentityAndActualRetainedFields() {
        val fixture = """{"state":"ON","brightness":100,"hsv":[0,0,100],"color_mode":"color_temp","color_temp_kelvin":2700,"ip":"192.168.88.116","features":{"state":true,"brightness":100,"color_temperature":2700,"hsv":[0,0,100]}}"""
        val d = TopicDiscovery.parse("kasa/3c64cf562bf6/state", fixture.toByteArray()).device!!
        assertEquals("kasa:3C64CF562BF6", d.key)
        assertEquals(DeviceKind.KASA, d.kind)
        assertTrue(d.power!!)
        assertEquals(100, d.level)
        assertEquals(0, d.hue)
        assertEquals(0, d.saturation)
        assertEquals(2700, d.colorTemperatureKelvin)
    }

    @Test fun kasaBulbBasesRemainIndependentAndAvailabilityMapsOnline() {
        val a = TopicDiscovery.parse("kasa/3c64cf562bf6/availability", "online".toByteArray()).device!!
        val b = TopicDiscovery.parse("kasa/3c64cf563ce3/availability", "offline".toByteArray()).device!!
        assertEquals("kasa:3C64CF562BF6", a.key)
        assertEquals("kasa:3C64CF563CE3", b.key)
        assertTrue(a.online)
        assertFalse(b.online)
    }

    @Test fun invalidKasaTopicsAreNotRecognizedAsKasa() {
        assertFalse(TopicDiscovery.parse("kasa/3c64cf562bf/state", "{}".toByteArray()).recognized)
        assertFalse(TopicDiscovery.parse("kasa/3c64cf562bfz/state", "{}".toByteArray()).recognized)
        assertFalse(TopicDiscovery.parse("kasa/3c64cf562bf6/response", "{}".toByteArray()).recognized)
    }
}
