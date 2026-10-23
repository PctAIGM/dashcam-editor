package com.dashcam.editor.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingWatchdogTest {
    @Test fun repeatedStatisticsAtEndDoNotKeepStalledEncoderAlive() {
        val watchdog = EncodingWatchdog(0, 120_000)
        assertFalse(watchdog.isStalled(1_000, 10_000, 300, 4_000_000))
        assertFalse(watchdog.isStalled(120_999, 10_000, 300, 4_000_000))
        assertTrue(watchdog.isStalled(121_000, 10_000, 300, 4_000_000))
    }

    @Test fun longSlowEncodeHasNoTotalDurationLimit() {
        val watchdog = EncodingWatchdog(0, 120_000)
        for (i in 1..120) {
            assertFalse(watchdog.isStalled(i * 60_000L, i * 100L, i, i * 1024L))
        }
    }

    @Test fun outputGrowthDuringFinalizationCountsAsWork() {
        val watchdog = EncodingWatchdog(0, 120_000)
        assertFalse(watchdog.isStalled(1_000, 10_000, 300, 4_000_000))
        assertFalse(watchdog.isStalled(120_000, 10_000, 300, 4_100_000))
        assertFalse(watchdog.isStalled(239_999, 10_000, 300, 4_100_000))
        assertTrue(watchdog.isStalled(240_000, 10_000, 300, 4_100_000))
    }

    @Test fun noFirstFrameEventuallyTimesOut() {
        val watchdog = EncodingWatchdog(50_000, 120_000)
        assertFalse(watchdog.isStalled(169_999, 0, 0, 0))
        assertTrue(watchdog.isStalled(170_000, 0, 0, 0))
    }

    @Test fun frameAdvanceCountsEvenWithUnchangedTimeAndBufferedOutput() {
        val watchdog = EncodingWatchdog(0, 120_000)
        assertFalse(watchdog.isStalled(100_000, 0, 1, 0))
        assertFalse(watchdog.isStalled(200_000, 0, 2, 0))
        assertTrue(watchdog.isStalled(320_000, 0, 2, 0))
    }
}
