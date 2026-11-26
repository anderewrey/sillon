package io.github.inobody92.sillon.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class KnownCallersTest {

    @Test fun lesEmpreintesSontDesSha256EnHexadecimal() {
        val all = KnownCallers.KEYS.values.flatten()
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue(it, Regex("^[0-9a-f]{64}$").matches(it)) }
    }
}
