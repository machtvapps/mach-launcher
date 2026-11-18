package io.github.terminalis.machlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPathTest {
    private val lightFrom = 7 * 60
    private val darkFrom = 19 * 60

    @Test
    fun `the clock rises on the left at Light from and is highest at midday`() {
        assertEquals(SkyPosition(0f, 0f), skyPosition(lightFrom, lightFrom, darkFrom))
        val midday = skyPosition(13 * 60, lightFrom, darkFrom)
        assertEquals(0.5f, midday.across, 0.001f)
        assertEquals(1f, midday.height, 0.001f)
    }

    @Test
    fun `the clock sets on the right just before Dark from, then starts again on the left`() {
        val dusk = skyPosition(darkFrom - 1, lightFrom, darkFrom)
        assertTrue(dusk.across > 0.99f)
        assertTrue(dusk.height < 0.01f)
        assertEquals(SkyPosition(0f, 0f), skyPosition(darkFrom, lightFrom, darkFrom))
    }

    @Test
    fun `the night path runs across midnight`() {
        val oneAm = skyPosition(60, lightFrom, darkFrom)
        assertEquals(0.5f, oneAm.across, 0.001f)
        assertEquals(1f, oneAm.height, 0.001f)
        assertTrue(skyPosition(6 * 60 + 59, lightFrom, darkFrom).across > 0.99f)
    }

    @Test
    fun `the clock only moves right through each path`() {
        var last = -1f
        for (minute in lightFrom until darkFrom) {
            val across = skyPosition(minute, lightFrom, darkFrom).across
            assertTrue(across > last)
            last = across
        }
    }

    @Test
    fun `equal times give one path over the whole day`() {
        assertEquals(0.5f, skyPosition(19 * 60, 7 * 60, 7 * 60).across, 0.001f)
    }

    @Test
    fun `a light or dark background fixes the clouds whatever the time`() {
        for (minutes in listOf(60, 13 * 60)) {
            assertTrue(showsDayClouds(BackgroundChoice.light, minutes, lightFrom, darkFrom))
            assertTrue(showsDayClouds(BackgroundChoice.cloudsDay, minutes, lightFrom, darkFrom))
            assertFalse(showsDayClouds(BackgroundChoice.dark, minutes, lightFrom, darkFrom))
            assertFalse(showsDayClouds(BackgroundChoice.cloudsNight, minutes, lightFrom, darkFrom))
        }
    }

    @Test
    fun `auto clouds and a custom image follow the Light from and Dark from times`() {
        for (choice in listOf(BackgroundChoice.cloudsAuto, BackgroundChoice.picture)) {
            assertTrue(showsDayClouds(choice, 13 * 60, lightFrom, darkFrom))
            assertFalse(showsDayClouds(choice, 60, lightFrom, darkFrom))
        }
    }
}
