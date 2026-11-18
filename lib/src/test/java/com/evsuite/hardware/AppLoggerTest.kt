package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLoggerTest {

    @Test
    fun `record fills the buffer and prints nothing to logcat`() {
        AppLogger.clear()
        ShadowLog.clear()

        AppLogger.record("MIRROR", AppLogger.Level.WARN, "from the app's own logger")

        val entry = AppLogger.entries.single()
        assertEquals("MIRROR", entry.tag)
        assertEquals(AppLogger.Level.WARN, entry.level)
        assertTrue(AppLogger.dump().contains("WARN/MIRROR: from the app's own logger"))
        assertTrue(ShadowLog.getLogsForTag("MIRROR").isEmpty())
    }

    @Test
    fun `concurrent writers keep well-formed timestamps`() {
        AppLogger.clear()
        val start = CountDownLatch(1)
        val writers = (1..8).map { n ->
            thread { start.await(); repeat(50) { AppLogger.record("T$n", AppLogger.Level.INFO, "line $it") } }
        }
        start.countDown()
        writers.forEach { it.join() }

        val timestamp = Regex("""\d{2}:\d{2}:\d{2}\.\d{3}""")
        AppLogger.entries.forEach { assertTrue("bad time '${it.time}'", timestamp.matches(it.time)) }
    }
}
