package com.runkeep.core

import kotlin.test.*

class AnnouncementTest {
    @Test fun `pace speech uses elapsed units and keeps display unchanged`() {
        assertEquals("6분 5초", paceSpeechText(365.0))
        assertEquals("6분", paceSpeechText(360.0))
        assertEquals("59초", paceSpeechText(59.0))
        assertEquals("60분", paceSpeechText(3600.0))
        assertEquals("6:05", paceText(365.0))
        assertEquals("6분", paceSpeechText(359.5))
        assertEquals("5분 59초", paceSpeechText(359.49))
        assertEquals("6분 4초", paceSpeechText(364.5)) // Same ties-to-even rule as display.
        assertEquals("6분 5초", paceSpeechText(364.51))
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 3600.01, .1).forEach {
            assertEquals("측정 불가", paceSpeechText(it))
        }
    }
    @Test fun `elapsed duration never becomes a clock time`() {
        assertEquals("1시간 1분 5초", durationSpeechText(3_665_000))
        assertEquals("1시간", durationSpeechText(3_600_000))
        assertEquals("1분", durationSpeechText(60_999))
        assertEquals("0초", durationSpeechText(-1))
    }
    @Test fun `average current and target pace retain their meaning`() {
        assertEquals("1킬로미터, 평균 페이스 6분 5초", Announcement.Kilometer(1, 365.0, 10).speechText())
        assertEquals("현재 페이스 6분", Announcement.Pace(AnnouncementKind.CURRENT_PACE, 360.0, 10).speechText())
        assertEquals("목표 페이스 6분 30초", Announcement.Pace(AnnouncementKind.TARGET_PACE, 390.0, 10).speechText())
        assertEquals("달리기, 목표 페이스 6분 5초", Announcement.Step("달리기", 365.0, 10).speechText())
    }
    @Test fun `navigation and state have priority without deleting milestones`() {
        val kilometer = Announcement.Kilometer(1, 365.0, 0)
        val step = Announcement.Step("다음 구간", 360.0, 0)
        val navigation = Announcement.Message(AnnouncementKind.NAVIGATION, "오른쪽으로 이동하세요", 0)
        val state = Announcement.Message(AnnouncementKind.STATE, "달리기를 재개합니다", 0)
        val pending = ArrayDeque(listOf(kilometer, step, navigation, state))
        val queue = AnnouncementQueue(pending)
        assertSame(state, queue.next(1000, RunStatus.RUNNING))
        queue.acknowledge(state)
        assertSame(navigation, queue.next(1000, RunStatus.RUNNING))
        queue.acknowledge(navigation)
        assertSame(kilometer, queue.next(1000, RunStatus.RUNNING))
        queue.acknowledge(kilometer)
        assertSame(step, queue.next(1000, RunStatus.RUNNING))
    }
    @Test fun `busy or failed playback keeps selected cue and drops stale sampled guidance`() {
        val kilometer = Announcement.Kilometer(1, 365.0, 0)
        val pending = ArrayDeque<Announcement>(listOf(
            Announcement.Pace(AnnouncementKind.CURRENT_PACE, 350.0, 0), kilometer,
            Announcement.Message(AnnouncementKind.NAVIGATION, "오른쪽으로 이동하세요", 0)))
        val queue = AnnouncementQueue(pending)
        assertEquals(AnnouncementKind.NAVIGATION, queue.next(1000, RunStatus.RUNNING)?.kind)
        assertEquals(3, pending.size) // Selection is not acceptance by TTS.
        assertSame(kilometer, queue.next(15_001, RunStatus.RUNNING))
        assertEquals(listOf(kilometer), pending.toList())
    }
    @Test fun `pause removes sampled pace and navigation and retains latest state and step`() {
        val latest = Announcement.Pace(AnnouncementKind.CURRENT_PACE, 360.0, 5)
        val resume = Announcement.Message(AnnouncementKind.STATE, "달리기를 재개합니다", 5)
        val step = Announcement.Step("두 번째", 360.0, 5)
        val pending = ArrayDeque<Announcement>(listOf(
            Announcement.Pace(AnnouncementKind.CURRENT_PACE, 350.0, 0), latest,
            Announcement.Step("첫 번째", 390.0, 0), step,
            Announcement.Message(AnnouncementKind.STATE, "자동 일시정지", 0), resume))
        val queue = AnnouncementQueue(pending)
        assertSame(resume, queue.next(5, RunStatus.RUNNING))
        assertTrue(latest in pending)
        assertEquals(3, pending.size)
        queue.next(6, RunStatus.PAUSED)
        assertFalse(latest in pending)
        assertTrue(step in pending)
    }
    @Test fun `real kilometer and step transitions use common speech formatter`() {
        val plan = WorkoutPlan(name = "계획", steps = listOf(
            PlanStep(kind = StepKind.TIME, amount = 10.0, paceSeconds = 360),
            PlanStep(name = "다음", kind = StepKind.DISTANCE, amount = 5000.0, paceSeconds = 365)))
        val engine = RunEngine(RunSummary(startedAt = 0, meters = 999.0, activeMs = 364_635, plan = plan), Preferences(autoPause = false))
        engine.tick(0, 0)
        engine.ingest(LocationFix(Coordinate(0.0, 127.0), 0, 0, 3f, speed = 3f))
        engine.ingest(LocationFix(Coordinate(3 / 111_195.0802335329, 127.0), 1000, 1000, 3f, speed = 3f))
        assertEquals("다음, 목표 페이스 6분 5초", engine.announcements.first { it is Announcement.Step }.speechText())
        val kilometer = engine.announcements.filterIsInstance<Announcement.Kilometer>().single()
        assertEquals("1킬로미터, 평균 페이스 ${paceSpeechText(engine.summary.averagePace)}", kilometer.speechText())
        assertFalse(kilometer.speechText().contains(":"))
    }
    @Test fun `navigation waits for a full sentence and keeps priority after completion`() {
        val warning = Announcement.Message(AnnouncementKind.PACE_WARNING, "현재 페이스가 빠릅니다. 현재 5분, 목표 6분입니다.", 0)
        val navigation = Announcement.Message(AnnouncementKind.NAVIGATION, "좌회전하세요", 1000)
        val step = Announcement.Step("다음 구간", 360.0, 1000)
        val pending = ArrayDeque<Announcement>(listOf(warning))
        val queue = AnnouncementQueue(pending)
        assertSame(warning, queue.next(0, RunStatus.RUNNING))
        queue.acknowledge(warning) // 재생 중인 문장은 플레이어가 소유한다.
        pending += step; pending += navigation
        for (now in 1000L..5000L step 1000) {
            assertNull(queue.next(now, RunStatus.RUNNING, playbackBusy = true))
            assertEquals(listOf(step, navigation), pending.toList())
        }
        assertSame(navigation, queue.next(5000, RunStatus.RUNNING, playbackBusy = false))
        queue.acknowledge(navigation)
        assertSame(step, queue.next(5000, RunStatus.RUNNING))
    }
    @Test fun `expired turn is discarded while a sentence finishes without skipping milestones`() {
        val turn = Announcement.Message(AnnouncementKind.NAVIGATION, "좌회전하세요", 0)
        val kilometer = Announcement.Kilometer(1, 360.0, 0)
        val pending = ArrayDeque<Announcement>(listOf(turn, kilometer))
        val queue = AnnouncementQueue(pending)
        assertNull(queue.next(15_001, RunStatus.RUNNING, playbackBusy = true))
        assertEquals(listOf(kilometer), pending.toList())
        assertSame(kilometer, queue.next(16_000, RunStatus.RUNNING))
    }
}
