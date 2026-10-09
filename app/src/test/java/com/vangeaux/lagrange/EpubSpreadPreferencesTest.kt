package com.vangeaux.lagrange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class EpubSpreadPreferencesTest {
    @Test
    fun `spread css properties force two columns independent of the 60em media query`() {
        val css = epubSpreadRsProperties(twoPageSpread = true).toCssProperties()
            .filterValues { it != null }

        assertEquals("2", css["--RS__colCount"])
        assertEquals("auto", css["--RS__colWidth"])
        assertEquals("24px", css["--RS__pageGutter"])
        // A column gap would break Readium's viewport-width pagination.
        assertFalse(css.containsKey("--RS__colGap"))
        assertEquals(setOf("--RS__colCount", "--RS__colWidth", "--RS__pageGutter"), css.keys)
    }

    @Test
    fun `single page css properties are Readium defaults`() {
        assertTrue(
            epubSpreadRsProperties(twoPageSpread = false).toCssProperties()
                .filterValues { it != null }
                .isEmpty()
        )
    }

    @Test
    fun `spread is active only for paginated layout`() {
        assertTrue(epubSpreadActive(true, ReaderLayoutMode.PAGINATED))
        assertFalse(epubSpreadActive(true, ReaderLayoutMode.CONTINUOUS))
        assertFalse(epubSpreadActive(false, ReaderLayoutMode.PAGINATED))
        assertFalse(epubSpreadActive(false, ReaderLayoutMode.CONTINUOUS))
    }

    @Test
    fun `spread padding uses the mean of left and right so the gutter stays centred`() {
        assertEquals(10f to 30f, epubHorizontalPaddingPercents(10f, 30f, twoPageSpread = false))
        assertEquals(20f to 20f, epubHorizontalPaddingPercents(10f, 30f, twoPageSpread = true))
        assertEquals(0f to 100f, epubHorizontalPaddingPercents(-5f, 150f, twoPageSpread = false))
        assertEquals(50f to 50f, epubHorizontalPaddingPercents(-5f, 150f, twoPageSpread = true))
    }
}
