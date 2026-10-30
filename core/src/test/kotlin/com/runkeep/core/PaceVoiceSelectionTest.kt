package com.runkeep.core

import kotlin.test.*

class PaceVoiceSelectionTest {
    private val prefs = Preferences(paceAlarm = true)
    private fun PaceFeedback.sample(now: Long, settings: Preferences = prefs, pace: Double = 380.0) =
        evaluate(now, true, pace, 360, now, settings)

    @Test fun `all eight voice selections contain exactly the selected content`() {
        for (status in listOf(false, true)) for (current in listOf(false, true)) for (target in listOf(false, true)) {
            val settings = prefs.copy(readPaceStatus = status, readCurrentPace = current, readTargetPace = target)
            val f = PaceFeedback()
            assertFalse(f.sample(0, settings).voiceDue)
            val state = f.sample(10_000, settings)
            val message = paceWarningAnnouncement(state, settings, 10_000)
            assertEquals(status || current || target, state.voiceDue)
            assertEquals(status || current || target, message != null)
            assertEquals(PaceDirection.SLOW, state.alarm)
            if (message != null) {
                assertEquals(AnnouncementKind.PACE_WARNING, message.kind)
                assertEquals(10_000L, message.occurredAtMs)
                val text = message.speechText()
                assertTrue(text.isNotBlank())
                assertEquals(status, text.contains("현재 페이스가 느립니다"))
                assertEquals(current, text.contains("현재 6분 20초"))
                assertEquals(target, text.contains("목표 6분"))
                assertFalse(text.contains(":"))
            }
        }
    }

    @Test fun `numeric only voice still requires ten seconds and stays silent in normal range`() {
        for (current in listOf(false, true)) for (target in listOf(false, true)) {
            val settings = prefs.copy(readPaceStatus = false, readCurrentPace = current, readTargetPace = target)
            val f = PaceFeedback()
            f.sample(0, settings)
            assertFalse(f.sample(9_999, settings).voiceDue)
            assertEquals(current || target, f.sample(10_000, settings).voiceDue)
            val normal = f.sample(10_001, settings, 360.0)
            assertNull(normal.alarm)
            assertFalse(normal.voiceDue)
            assertNull(paceWarningAnnouncement(normal, settings, 10_001))
        }
    }

    @Test fun `fast status and default settings preserve previous wording`() {
        val state = PaceFeedbackState(PaceDirection.FAST, currentPace = 330.0, targetPace = 360)
        assertEquals("현재 페이스가 빠릅니다", paceWarningAnnouncement(state, prefs, 0)!!.speechText())
        assertEquals("현재 페이스가 빠릅니다. 현재 5분 30초, 목표 6분입니다.",
            paceWarningAnnouncement(state, prefs.copy(readCurrentPace = true, readTargetPace = true), 0)!!.speechText())
        assertEquals("현재 5분 30초, 목표 6분입니다.", paceWarningAnnouncement(state,
            prefs.copy(readPaceStatus = false, readCurrentPace = true, readTargetPace = true), 0)!!.speechText())
    }

    @Test fun `invalid selected current pace is omitted without an empty announcement`() {
        val settings = prefs.copy(readPaceStatus = false, readCurrentPace = true)
        for (pace in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.1, 0.5, 3601.0)) {
            val state = PaceFeedbackState(PaceDirection.SLOW, currentPace = pace, targetPace = 360)
            assertNull(paceWarningAnnouncement(state, settings, 0))
            assertEquals("목표 6분입니다.",
                paceWarningAnnouncement(state, settings.copy(readTargetPace = true), 0)!!.speechText())
        }
    }

    @Test fun `invalid selected target is omitted and target limits remain eligible`() {
        val settings = prefs.copy(readPaceStatus = false, readTargetPace = true)
        for (target in listOf(null, Int.MIN_VALUE, -1, 0, 119, 1801, Int.MAX_VALUE)) {
            val state = PaceFeedbackState(PaceDirection.SLOW, currentPace = 380.0, targetPace = target)
            assertNull(paceWarningAnnouncement(state, settings, 0))
            assertEquals("현재 6분 20초입니다.",
                paceWarningAnnouncement(state, settings.copy(readCurrentPace = true), 0)!!.speechText())
        }
        for (target in listOf(120, 1800)) {
            val f = PaceFeedback()
            assertEquals(PaceDirection.SLOW, f.evaluate(0, true, target + 20.0, target, 0, settings).alarm)
            assertTrue(f.evaluate(10_000, true, target + 20.0, target, 10_000, settings).voiceDue)
        }
    }

    @Test fun `all off callbacks cannot consume the first voice request or spoken interval`() {
        val f = PaceFeedback()
        val off = prefs.copy(readPaceStatus = false)
        f.sample(0, off)
        assertFalse(f.sample(10_000, off).voiceDue)
        f.requested()
        f.spoken(10_000)
        assertTrue(f.sample(10_001).voiceDue)
        f.requested()
        assertFalse(f.sample(10_002).voiceDue)
        f.completed()
        assertTrue(f.sample(10_003).voiceDue)
    }

    @Test fun `callbacks before voice sustain cannot suppress or postpone an eligible warning`() {
        val f = PaceFeedback()
        f.sample(0)
        f.requested()
        f.spoken(9_000)
        assertTrue(f.sample(10_000).voiceDue)
    }

    @Test fun `callbacks after selected content disappears cannot consume repeat interval`() {
        val f = PaceFeedback()
        f.sample(0)
        f.sample(10_000)
        f.requested()
        assertFalse(f.sample(10_001, prefs.copy(readPaceStatus = false)).voiceDue)
        f.spoken(10_002)
        f.completed()
        assertTrue(f.sample(10_003).voiceDue)
    }

    @Test fun `live content changes use the latest values and retain actual spoken repeat time`() {
        val f = PaceFeedback()
        f.sample(0)
        f.sample(10_000)
        f.requested()
        f.spoken(12_000)
        f.completed()
        val numeric = prefs.copy(readPaceStatus = false, readCurrentPace = true)
        val before = f.sample(41_999, numeric, 390.0)
        assertFalse(before.voiceDue)
        val due = f.sample(42_000, numeric, 400.0)
        assertTrue(due.voiceDue)
        assertEquals("현재 6분 40초입니다.", paceWarningAnnouncement(due, numeric, 42_000)!!.speechText())
        assertFalse(f.sample(42_001, numeric.copy(readCurrentPace = false)).voiceDue)
        assertTrue(f.sample(42_002, numeric.copy(readTargetPace = true)).voiceDue)
    }

    @Test fun `live repeat interval changes use the actual spoken timestamp`() {
        val f = PaceFeedback()
        f.sample(0)
        f.sample(10_000)
        f.spoken(12_000)
        assertFalse(f.sample(22_000, prefs.copy(paceRepeatSeconds = 120)).voiceDue)
        assertTrue(f.sample(22_001, prefs.copy(paceRepeatSeconds = 10)).voiceDue)
        assertFalse(f.sample(22_002, prefs.copy(paceRepeatSeconds = 30)).voiceDue)
        assertTrue(f.sample(132_000, prefs.copy(paceRepeatSeconds = 120)).voiceDue)
    }

    @Test fun `global and whole pace voice toggles leave alarms independent`() {
        for (voice in listOf(false, true)) for (paceVoice in listOf(false, true)) {
            val f = PaceFeedback()
            val settings = prefs.copy(voice = voice, paceVoice = paceVoice, readCurrentPace = true, readTargetPace = true)
            f.sample(0, settings)
            val state = f.sample(10_000, settings)
            assertEquals(voice && paceVoice, state.voiceDue)
            assertEquals(voice && paceVoice, paceWarningAnnouncement(state, settings, 10_000) != null)
            assertEquals(PaceDirection.SLOW, state.alarm)
        }
    }
}
