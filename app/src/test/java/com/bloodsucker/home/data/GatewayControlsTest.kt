package com.bloodsucker.home.data

import org.junit.Assert.*
import org.junit.Test

class GatewayControlsTest {
    @Test fun parsesEsphomeSwitchLightAndNumberControls() {
        val controls = GatewayControls.parse("""[
          {"topic":"clock/switch/live/state","writable":true,"control":"switch","value":"ON","last_seen":1},
          {"topic":"clock/light/status/state","writable":true,"control":"light","value":"{\"state\":\"ON\",\"brightness\":122}","last_seen":2},
          {"topic":"clock/number/gain/state","writable":true,"control":"number","value":"4.5","last_seen":3}
        ]""")
        assertEquals(3, controls.size)
        assertTrue(controls.first { it.controlType == "switch" }.power == true)
        assertEquals(122, controls.first { it.controlType == "light" }.level)
        assertEquals(4.5, controls.first { it.controlType == "number" }.numericValue!!, 0.0)
    }

    @Test fun gatewaySeedsWledLightsWhileExcludingOtherNativeControls() {
        val controls = GatewayControls.parse("""[
          {"topic":"wled/abcdef/status","name":"Kitchen","writable":true,"control":"wled","value":"online","last_seen":4},
          {"topic":"matter/1/1/514/2","writable":true,"control":"percent","value":"0"},
          {"topic":"wol/aabbccddeeff/","writable":true,"control":"trigger","value":"registered"}
        ]""")
        assertEquals(1, controls.size)
        assertEquals("wled:ABCDEF", controls.single().key)
        assertEquals("Kitchen", controls.single().name)
        assertTrue(controls.single().online)
    }
}
