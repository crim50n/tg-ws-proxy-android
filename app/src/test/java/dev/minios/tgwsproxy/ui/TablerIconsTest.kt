package dev.minios.tgwsproxy.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.ui.icons.TablerIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablerIconsTest {
    @Test
    fun allVendoredIconsParseIntoNonEmptyVectors() {
        val getters =
            TablerIcons::class.java.methods.filter {
                it.returnType == ImageVector::class.java && it.parameterCount == 0
            }
        assertTrue(getters.isNotEmpty())
        for (getter in getters) {
            val icon = getter.invoke(TablerIcons) as ImageVector
            assertEquals(icon.name, 24.dp, icon.defaultWidth)
            assertEquals(icon.name, 24.dp, icon.defaultHeight)
            assertTrue(icon.name, icon.root.size > 0)
        }
    }

    @Test
    fun backArrowMirrorsButPlaybackDoesNot() {
        assertTrue(TablerIcons.ArrowLeft.autoMirror)
        assertTrue(!TablerIcons.PlayerPlay.autoMirror)
    }
}
