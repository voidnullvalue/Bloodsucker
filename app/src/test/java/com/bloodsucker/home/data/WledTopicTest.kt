package com.bloodsucker.home.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WledTopicTest {
    @Test fun internalUppercaseIdPublishesToFixtureLowercaseTopic() {
        assertEquals("wled/1df0ec", wledMqttTopic("wled:1DF0EC"))
    }
}
