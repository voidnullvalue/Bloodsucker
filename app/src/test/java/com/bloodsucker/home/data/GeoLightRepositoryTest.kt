package com.bloodsucker.home.data

import com.bloodsucker.home.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class GeoLightRepositoryTest {
    private class FakePreferences : HomePreferences {
        val strings = mutableMapOf<String, String>()
        val sets = mutableMapOf<String, Set<String>>()
        override fun getString(key: String, defaultValue: String?) = strings[key] ?: defaultValue
        override fun getStringSet(key: String, defaultValue: Set<String>) = sets[key] ?: defaultValue
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putStringSet(key: String, values: Set<String>) { sets[key] = values.toSet() }
    }

    private class Published(val topic: String, val payload: String)
    private class Pending(val topic: String, val payload: String, val complete: (Boolean) -> Unit)

    private class FakeTransport : HomeTransport {
        private var onConnection: ((Boolean, String?) -> Unit)? = null
        private var onMessage: ((String, ByteArray, Boolean) -> Unit)? = null
        var connected = false
        var failPublish = false
        var autoCompleteGeo = true
        val published = mutableListOf<Published>()
        val pending = mutableListOf<Pending>()

        override fun setListener(onConnection: (Boolean, String?) -> Unit, onMessage: (String, ByteArray, Boolean) -> Unit) {
            this.onConnection = onConnection; this.onMessage = onMessage
        }
        override fun connect(uriText: String, clientId: String) { connected = true; onConnection?.invoke(true, null) }
        override fun disconnect() { connected = false; onConnection?.invoke(false, null) }
        override fun publish(topic: String, payload: String): Boolean {
            if (!connected || failPublish) return false
            published += Published(topic, payload); return true
        }
        override fun publishWithCompletion(topic: String, payload: String, onComplete: (Boolean) -> Unit): Boolean {
            if (!connected || failPublish) return false
            published += Published(topic, payload)
            if (autoCompleteGeo) onComplete(true) else pending += Pending(topic, payload, onComplete)
            return true
        }
        fun completeNext(success: Boolean) { pending.removeAt(0).complete(success) }
        fun emit(topic: String, payload: String, retained: Boolean = false) = onMessage?.invoke(topic, payload.toByteArray(), retained)
    }

    private class Clock(var now: Long = 1_000L)

    private class Harness {
        val prefs = FakePreferences()
        val transport = FakeTransport()
        val clock = Clock()
        var gatewayDevices: List<SmartDevice> = emptyList()
        val repo = HomeRepository(
            prefs,
            { transport },
            { gatewayDevices },
            { WledMetadata() },
            autoStart = false,
            clock = { clock.now }
        )

        init { repo.connect() }

        val geo: SmartDevice get() = repo.state.value.devices.first { it.key == EsphomeLight.KEY }
        fun emit(topic: String, payload: String, retained: Boolean = false) = transport.emit(topic, payload, retained)
        fun lastPublish(): Published = transport.published.last()
    }

    @Test fun partialStateKeepsPriorValuesAndNeverFabricatesPower() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""")
        assertEquals(true, h.geo.power)
        assertEquals(128, h.geo.level)
        assertEquals("Campfire", h.geo.effect)

        h.emit(EsphomeLight.STATE_TOPIC, """{"brightness":0}""")
        assertEquals(true, h.geo.power)
        assertEquals(0, h.geo.level)
        assertEquals("Campfire", h.geo.effect)

        h.emit(EsphomeLight.STATE_TOPIC, """{"brightness":256,"effect":"Disco"}""")
        assertEquals(0, h.geo.level)
        assertEquals("Campfire", h.geo.effect)

        h.emit(EsphomeLight.STATE_TOPIC, "{not json")
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"BOGUS"}""")
        assertEquals(true, h.geo.power)
        assertEquals(0, h.geo.level)
        assertEquals("Campfire", h.geo.effect)

        val fresh = Harness()
        fresh.emit(EsphomeLight.STATE_TOPIC, """{"brightness":128}""")
        assertNull(fresh.geo.power)
        assertEquals(128, fresh.geo.level)
        assertNull(fresh.geo.effect)
    }

    @Test fun onOffBrightnessAndEffectUseExactMinimalCommands() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        val geo = h.geo

        assertTrue(h.repo.setPower(geo, true))
        assertEquals(EsphomeLight.COMMAND_TOPIC, h.lastPublish().topic)
        assertEquals("""{"state":"ON"}""", h.lastPublish().payload)
        assertTrue(h.repo.setPower(geo, false))
        assertEquals("""{"state":"OFF"}""", h.lastPublish().payload)

        assertTrue(h.repo.setLevel(geo, 0))
        assertEquals("""{"state":"ON","brightness":0}""", h.lastPublish().payload)
        assertTrue(h.repo.setLevel(geo, 255))
        assertEquals("""{"state":"ON","brightness":255}""", h.lastPublish().payload)
        assertTrue(h.repo.setEsphomePercent(geo, 0))
        assertEquals("""{"state":"ON","brightness":0}""", h.lastPublish().payload)
        assertTrue(h.repo.setEsphomePercent(geo, 100))
        assertEquals("""{"state":"ON","brightness":255}""", h.lastPublish().payload)
        assertTrue(h.repo.setEsphomePercent(geo, 50))
        assertEquals("""{"state":"ON","brightness":128}""", h.lastPublish().payload)
        assertEquals(128, EsphomeLight.percentToBrightness(50))

        assertTrue(h.repo.setEsphomeEffect(geo, "Turbulent Flame"))
        assertEquals("""{"state":"ON","effect":"Turbulent Flame"}""", h.lastPublish().payload)
        assertTrue(h.repo.setEsphomeEffect(geo, "None"))
        assertEquals("""{"state":"ON","effect":"None"}""", h.lastPublish().payload)
        assertFalse(h.repo.setEsphomeEffect(geo, "Steady illumination"))
        assertFalse(h.repo.setEsphomeEffect(geo, "Disco"))
        assertEquals("""{"state":"ON","effect":"None"}""", h.lastPublish().payload)
    }

    @Test fun invalidKeyUnknownEffectAndPublishFailureDoNotOptimisticallyChangeState() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""")
        val other = h.geo.copy(key = "esphome:other")
        assertFalse(h.repo.setPower(other, false))
        assertFalse(h.repo.setLevel(other, 10))
        assertEquals(0, h.transport.published.size)

        val geo = h.geo
        h.transport.failPublish = true
        assertFalse(h.repo.setPower(geo, false))
        assertEquals(true, h.geo.power)
        assertFalse(h.repo.setLevel(geo, 10))
        assertEquals(128, h.geo.level)
        assertFalse(h.repo.setEsphomeEffect(geo, "Candle"))
        assertEquals("Campfire", h.geo.effect)
        assertNotNull(h.repo.state.value.lastError)
        assertFalse(h.repo.setGeneric(
            SmartDevice("control:${EsphomeLight.STATE_TOPIC}", DeviceKind.CONTROL, "Fake Geo", stateTopic = EsphomeLight.STATE_TOPIC),
            "off"
        ))
    }

    @Test fun effectIsPreservedAndReconciledWithIncomingState() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""")
        val geo = h.geo
        assertTrue(h.repo.setEsphomeEffect(geo, "Candle"))
        assertEquals("Candle", h.geo.effect)
        assertEquals(true, h.geo.power)

        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":200}""")
        assertEquals("Candle", h.geo.effect)
        assertEquals(200, h.geo.level)

        h.emit(EsphomeLight.STATE_TOPIC, """{"effect":"None"}""")
        assertEquals("None", h.geo.effect)
        h.emit(EsphomeLight.STATE_TOPIC, """{"effect":"Unknown"}""")
        assertEquals("None", h.geo.effect)
        assertEquals("Steady illumination", EsphomeLight.displayLabel("None"))
        assertTrue(EsphomeLight.isFlameEffect("Campfire"))
        assertFalse(EsphomeLight.isFlameEffect("None"))
    }

    @Test fun availabilitySurvivesOrderingRetainedReplayAndDisconnects() {
        val h = Harness()
        h.clock.now = 100
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "offline")
        assertFalse(h.geo.online)
        assertTrue(h.geo.explicitAvailability)
        assertEquals(100, h.geo.lastSeen)

        h.clock.now = 200
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""", retained = true)
        assertFalse(h.geo.online)
        assertEquals(100, h.geo.lastSeen)
        assertEquals(128, h.geo.level)

        h.clock.now = 300
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "unknown")
        assertFalse(h.geo.online)
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        assertFalse(h.geo.online)

        h.clock.now = 400
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online")
        assertTrue(h.geo.online)
        assertEquals(400, h.geo.lastSeen)

        val reverse = Harness()
        reverse.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON"}""", retained = true)
        assertTrue(reverse.geo.online)
        reverse.emit(EsphomeLight.AVAILABILITY_TOPIC, "offline")
        assertFalse(reverse.geo.online)
        reverse.transport.disconnect()
        reverse.repo.connect()
        reverse.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON"}""", retained = true)
        assertFalse(reverse.geo.online)
        // A delayed retained online from the prior session must not win; a new
        // subscription's retained availability snapshot is allowed to recover it.
        reverse.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        assertTrue(reverse.geo.online)
    }

    @Test fun currentSessionOfflineBeatsStateAndRetainedOnline() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""")
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "offline")

        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"OFF","brightness":200}""")
        assertFalse(h.geo.online)
        assertEquals(false, h.geo.power)
        assertEquals(200, h.geo.level)

        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        assertFalse(h.geo.online)

        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online")
        assertTrue(h.geo.online)
    }

    @Test fun retainedFirstSnapshotsHaveUnknownLiveTimestamp() {
        val state = Harness()
        state.clock.now = 1_000
        state.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""", retained = true)
        assertEquals(0L, state.geo.lastSeen)

        val status = Harness()
        status.clock.now = 1_000
        status.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        assertEquals(0L, status.geo.lastSeen)
        assertTrue(status.geo.online)

        val replay = Harness()
        replay.clock.now = 500
        replay.emit(EsphomeLight.AVAILABILITY_TOPIC, "online")
        assertEquals(500L, replay.geo.lastSeen)
        replay.transport.disconnect()
        replay.repo.connect()
        replay.clock.now = 600
        replay.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        assertEquals(500L, replay.geo.lastSeen)
        assertTrue(replay.geo.online)
    }

    @Test fun geoOptimismWaitsForCompletedWriteAndWriteFailureDoesNotApplyState() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Campfire"}""")
        h.transport.autoCompleteGeo = false

        assertTrue(h.repo.setPower(h.geo, false))
        assertEquals(1, h.transport.pending.size)
        assertEquals(true, h.geo.power)
        h.transport.completeNext(true)
        assertEquals(false, h.geo.power)

        assertTrue(h.repo.setLevel(h.geo, 40))
        assertEquals(128, h.geo.level)
        h.transport.completeNext(false)
        assertEquals(128, h.geo.level)
        assertNotNull(h.repo.state.value.lastError)

        assertTrue(h.repo.setEsphomeEffect(h.geo, "Candle"))
        assertEquals("Campfire", h.geo.effect)
        h.transport.completeNext(true)
        assertEquals("Candle", h.geo.effect)
    }

    @Test fun authoritativeStateBetweenEnqueueAndCompletionWins() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        h.transport.autoCompleteGeo = false

        assertTrue(h.repo.setPower(h.geo, false))
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":200}""")
        h.transport.completeNext(true)
        assertEquals(true, h.geo.power)
        assertEquals(200, h.geo.level)

        assertTrue(h.repo.setPower(h.geo, false))
        h.transport.disconnect()
        h.transport.completeNext(true)
        assertEquals(true, h.geo.power)
        assertNotEquals(ConnectionState.CONNECTED, h.repo.state.value.connection)
    }

    @Test fun concurrentAuthoritativeMergeCannotBeOverwrittenByCompletion() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        h.transport.autoCompleteGeo = false
        assertTrue(h.repo.setPower(h.geo, false))

        val guardPassed = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        h.repo.geoCompletionCheckHook = {
            guardPassed.countDown()
            allowCommit.await()
        }

        val completion = thread(name = "geo-completion") { h.transport.completeNext(true) }
        assertTrue(guardPassed.await(2, TimeUnit.SECONDS))

        val emitter = thread(name = "geo-authoritative") {
            h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":200}""")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (emitter.isAlive && emitter.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        allowCommit.countDown()
        completion.join(2_000)
        emitter.join(2_000)
        assertFalse(completion.isAlive)
        assertFalse(emitter.isAlive)
        // The authoritative state message must win even though the completion had
        // already passed its revision/epoch/connection guard.
        assertEquals(true, h.geo.power)
        assertEquals(200, h.geo.level)
    }

    @Test fun staleDeviceSnapshotCannotBypassOfflineOrDisconnect() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online")
        val stale = h.geo
        assertTrue(stale.online)

        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "offline")
        assertFalse(h.repo.setPower(stale, false))
        assertFalse(h.repo.setLevel(stale, 10))
        assertFalse(h.repo.setEsphomeEffect(stale, "Candle"))
        assertTrue(h.transport.published.none { it.topic == EsphomeLight.COMMAND_TOPIC })

        h.transport.disconnect()
        assertFalse(h.repo.setPower(stale, false))
        assertTrue(h.transport.published.none { it.topic == EsphomeLight.COMMAND_TOPIC })
    }

    @Test fun brightnessOnlyAcceptsJsonIntegersInRange() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"brightness":100}""")
        assertEquals(100, h.geo.level)

        listOf(
            """{"brightness":"200"}""",
            """{"brightness":true}""",
            """{"brightness":12.5}""",
            """{"brightness":256}""",
            """{"brightness":-1}"""
        ).forEach { payload ->
            h.emit(EsphomeLight.STATE_TOPIC, payload)
            assertEquals(payload, 100, h.geo.level)
        }

        h.emit(EsphomeLight.STATE_TOPIC, """{"brightness":255}""")
        assertEquals(255, h.geo.level)
    }

    @Test fun retainedReplayDoesNotFreshenLiveStateButLiveMessagesDo() {
        val h = Harness()
        h.clock.now = 100
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":10}""")
        assertEquals(100, h.geo.lastSeen)

        h.clock.now = 200
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":10}""", retained = true)
        assertEquals(100, h.geo.lastSeen)

        h.clock.now = 300
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"OFF"}""")
        assertEquals(300, h.geo.lastSeen)
        assertEquals(false, h.geo.power)
    }

    @Test fun disconnectBlocksPublishingAndReconnectReplayDoesNotDuplicateDevice() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online")
        assertEquals(1, h.repo.state.value.devices.count { it.key == EsphomeLight.KEY })

        h.transport.disconnect()
        assertNotEquals(ConnectionState.CONNECTED, h.repo.state.value.connection)
        val geo = h.geo
        assertFalse(h.repo.setPower(geo, false))
        assertFalse(h.repo.setEsphomePercent(geo, 50))
        assertTrue(h.transport.published.isEmpty())

        h.repo.connect()
        assertEquals(ConnectionState.CONNECTED, h.repo.state.value.connection)
        repeat(3) {
            h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""", retained = true)
            h.emit(EsphomeLight.AVAILABILITY_TOPIC, "online", retained = true)
        }
        assertEquals(1, h.repo.state.value.devices.count { it.key == EsphomeLight.KEY })
        assertTrue(h.repo.setPower(h.geo, false))
        assertEquals(EsphomeLight.COMMAND_TOPIC, h.lastPublish().topic)
    }

    @Test fun gatewayDuplicatesAreExcludedAndCannotOverrideNativeState() {
        val h = Harness()
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128,"effect":"Candle"}""")
        val native = h.geo

        h.gatewayDevices = listOf(
            SmartDevice("control:${EsphomeLight.STATE_TOPIC}", DeviceKind.CONTROL, "Geo gateway", power = false, level = 0, stateTopic = EsphomeLight.STATE_TOPIC),
            SmartDevice(EsphomeLight.KEY, DeviceKind.ESPHOME, "Stale Geo", online = false, power = false, level = 0, effect = "None"),
            SmartDevice("control:clock/light/state", DeviceKind.CONTROL, "Clock light", power = true, stateTopic = "clock/light/state")
        )
        h.repo.loadGatewayControls()

        assertEquals(1, h.repo.state.value.devices.count { it.key == EsphomeLight.KEY })
        val after = h.geo
        assertEquals(native.power, after.power)
        assertEquals(native.level, after.level)
        assertEquals(native.effect, after.effect)
        assertTrue(h.repo.state.value.devices.none { it.key == "control:${EsphomeLight.STATE_TOPIC}" })

        val parsed = GatewayControls.parse("""[
          {"topic":"geo-lamp/light/state","writable":true,"control":"light","value":"{\"state\":\"OFF\"}"},
          {"topic":"geo-lamp/light/command","writable":true,"control":"light","value":"ON"},
          {"topic":"geo-lamp/status","writable":true,"control":"switch","value":"offline"},
          {"topic":"clock/light/state","writable":true,"control":"light","value":"{\"state\":\"ON\"}"}
        ]""")
        assertEquals(1, parsed.size)
        assertEquals("control:clock/light/state", parsed.single().key)
    }

    @Test fun personalizationAndFavoritesArePreservedAcrossStateUpdates() {
        val h = Harness()
        h.prefs.putString("alias:${EsphomeLight.KEY}", "Patio Flame")
        h.prefs.putString("room:${EsphomeLight.KEY}", "Deck")
        h.prefs.putStringSet("favorites", setOf(EsphomeLight.KEY))

        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON","brightness":128}""")
        assertEquals("Patio Flame", h.geo.name)
        assertEquals("Deck", h.geo.room)
        assertTrue(h.geo.favorite)
        assertEquals(EsphomeLight.KEY, h.repo.state.value.devices.first().key)

        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"OFF"}""", retained = true)
        assertEquals("Patio Flame", h.geo.name)
        assertEquals("Deck", h.geo.room)
        assertTrue(h.geo.favorite)

        h.repo.toggleFavorite(EsphomeLight.KEY)
        assertFalse(h.geo.favorite)
        h.emit(EsphomeLight.STATE_TOPIC, """{"state":"ON"}""")
        assertFalse(h.geo.favorite)
    }

    @Test fun wledKasaAndGatewaySwitchesStillUseExistingPaths() {
        val h = Harness()
        h.emit("kasa/3c64cf562bf6/state", """{"state":"ON","brightness":100,"hsv":[0,0,100]}""")
        h.emit("wled/abcdef/g", "64")
        h.emit("gateway/device/switch-1/switch/state", "on")
        assertEquals(DeviceKind.KASA, h.repo.state.value.devices.first { it.key == "kasa:3C64CF562BF6" }.kind)
        assertEquals(DeviceKind.WLED, h.repo.state.value.devices.first { it.key == "wled:ABCDEF" }.kind)
        assertEquals(true, h.repo.state.value.devices.first { it.key == "gateway:switch-1" }.power)
        assertEquals(0, h.repo.state.value.devices.count { it.kind == DeviceKind.ESPHOME })

        val kasa = h.repo.state.value.devices.first { it.key == "kasa:3C64CF562BF6" }
        val wled = h.repo.state.value.devices.first { it.key == "wled:ABCDEF" }
        val gateway = h.repo.state.value.devices.first { it.key == "gateway:switch-1" }
        assertTrue(h.repo.setPower(kasa, false))
        assertEquals("kasa/3c64cf562bf6/set", h.lastPublish().topic)
        assertEquals("OFF", h.lastPublish().payload)
        assertTrue(h.repo.setPower(wled, false))
        assertEquals("wled/abcdef", h.lastPublish().topic)
        assertTrue(h.repo.setPower(gateway, false))
        assertEquals("gateway/device/switch-1/switch/set", h.lastPublish().topic)
        assertEquals("off", h.lastPublish().payload)
    }

    @Test fun arbitraryLightShapedJsonDoesNotCreateOrControlGeo() {
        val h = Harness()
        h.emit("other/light/state", """{"state":"ON","brightness":255,"effect":"Campfire"}""")
        h.emit("geo-lamp/light/state/extra", """{"state":"ON","brightness":255}""")
        assertTrue(h.repo.state.value.devices.none { it.kind == DeviceKind.ESPHOME })
        assertTrue(h.repo.state.value.devices.isEmpty())
        assertTrue(h.transport.published.isEmpty())
    }
}
