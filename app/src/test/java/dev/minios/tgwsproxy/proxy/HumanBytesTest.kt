package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class HumanBytesTest {
    @Test
    fun separatesValuesAndUnitsAtBinaryBoundaries() {
        assertEquals("%.1f B".format(0.0), MtProtoConstants.humanBytes(0))
        assertEquals("%.1f B".format(1023.0), MtProtoConstants.humanBytes(1023))
        assertEquals("%.1f KB".format(1.0), MtProtoConstants.humanBytes(1024))
        assertEquals("%.1f KB".format(1.5), MtProtoConstants.humanBytes(1536))
        assertEquals("%.1f MB".format(1.0), MtProtoConstants.humanBytes(1024L * 1024))
        assertEquals("%.1f GB".format(1.0), MtProtoConstants.humanBytes(1024L * 1024 * 1024))
        assertEquals("%.1f TB".format(1.0), MtProtoConstants.humanBytes(1024L * 1024 * 1024 * 1024))
    }
}
