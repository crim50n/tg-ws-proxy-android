package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoConstantsTest {
    @Test
    fun ordinarySessionsNeverUseMediaDomains() {
        for (dc in MtProtoConstants.ALL_DCS) {
            val wsDc = if (dc == 203) 2 else dc
            assertEquals(listOf("kws$wsDc.web.telegram.org"), MtProtoConstants.wsDomainsForDc(dc, false))
            assertEquals(
                listOf("kws$wsDc-1.web.telegram.org", "kws$wsDc.web.telegram.org"),
                MtProtoConstants.wsDomainsForDc(dc, true),
            )
        }
    }

    @Test
    fun optionsPrefixIsReserved() {
        assertTrue(MtProtoConstants.RESERVED_STARTS.any { it.contentEquals("OPTI".toByteArray()) })
    }
}
