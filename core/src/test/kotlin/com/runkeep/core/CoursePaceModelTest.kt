package com.runkeep.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class CoursePaceModelTest {
    @Test fun `ten thousand point combined drafts and snapshots round trip without losing identities`() {
        val large = Course(id = "large", name = "대형 코스", points = (0 until 10_000).map { coordinate(it.toDouble()) },
            pointIds = (0 until 10_000).map { "point-$it".padEnd(128, 'x') }).withAllPaces(360)
        val raw = codec.encodeToString(CourseEditorDraft(large))
        assertTrue(raw.length > 1_000_000)
        assertEquals(large, codec.decodeFromString<CourseEditorDraft>(raw).course)
        val file = codec.encodeToString(CourseFile(course = large))
        assertTrue(file.toByteArray(Charsets.UTF_8).size in (MAX_LEGACY_COURSE_FILE_BYTES + 1)..MAX_COURSE_FILE_BYTES)
        assertEquals(large, readCourseFile(file))
        assertFailsWith<IllegalArgumentException> { readCourseFile(file.replace("\"schemaVersion\":2", "\"schemaVersion\":1")) }
        val run = RunSummary(startedAt = 0, course = large, coursePaceEnabled = true)
        assertEquals(run, codec.decodeFromString<RunSummary>(codec.encodeToString(run)))
    }

    @Test fun `dragging through temporary orders restores targets for final adjacent pairs`() {
        val original = course()
        val id = original.pointIds[1]
        val temporary = CourseEditorDraft(original).move(id, 3, original.paceTargets)
        val restored = temporary.move(id, 1, original.paceTargets)
        assertEquals(original, restored.course)
        val final = temporary.move(id, 2, original.paceTargets)
        assertEquals(original.paceTargets.filter { it.fromPointId to it.toPointId in final.course.pointIds.zipWithNext() }, final.course.paceTargets)
    }

    private fun coordinate(meters: Double) = Coordinate(meters / 111195.08, 0.0)
    private fun course() = Course(id = "course", name = "코스", points = listOf(0.0, 100.0, 300.0, 600.0).map(::coordinate),
        pointIds = listOf("a", "b", "c", "d")).withPace(0, 360).withPace(1, 345).withPace(2, 420)

    @Test fun `moving a coordinate preserves edge identities and recalculates time`() {
        val original = course()
        val next = CourseEditorDraft(original).relocate("b", coordinate(200.0)).course
        assertEquals(original.pointIds, next.pointIds)
        assertEquals(listOf(360, 345, 420), (0..2).map(next::paceAt))
        assertNotEquals(original.paceEstimate().seconds, next.paceEstimate().seconds)
        assertEquals(232.5, next.paceEstimate().seconds!!, .001)
    }

    @Test fun `inserting a waypoint clears only the split edge`() {
        val original = course()
        val next = CourseEditorDraft(original).insert(1, coordinate(50.0)).course
        assertNull(next.paceAt(0)); assertNull(next.paceAt(1))
        assertEquals(345, next.paceAt(2)); assertEquals(420, next.paceAt(3))
        assertEquals(listOf("a", "b", "c", "d"), next.pointIds.filter { it in original.pointIds })
        assertEquals(5, next.pointIds.distinct().size)
        assertEquals(3, original.paceTargets.size)
    }

    @Test fun `deleting a waypoint does not transfer a target to the merged edge`() {
        val next = CourseEditorDraft(course()).remove("b").course
        assertEquals(listOf("a", "c", "d"), next.pointIds)
        assertNull(next.paceAt(0)); assertEquals(420, next.paceAt(1))
    }

    @Test fun `reordering retains only surviving directed adjacent pairs`() {
        val next = CourseEditorDraft(course()).move("d", 0).course
        assertEquals(listOf("d", "a", "b", "c"), next.pointIds)
        assertNull(next.paceAt(0)); assertEquals(360, next.paceAt(1)); assertEquals(345, next.paceAt(2))
        assertEquals(2, next.paceTargets.size)
    }

    @Test fun `reversing an edge never reuses its old target`() {
        val next = CourseEditorDraft(course()).move("a", 1).course
        assertEquals(listOf("b", "a", "c", "d"), next.pointIds)
        assertNull(next.paceAt(0)); assertNull(next.paceAt(1)); assertEquals(420, next.paceAt(2))
    }

    @Test fun `loop endpoints have different identities and targets`() {
        val loop = Course(id = "loop", name = "왕복", points = listOf(coordinate(0.0), coordinate(5.0), coordinate(0.0)))
            .withPace(0, 360).withPace(1, 345)
        assertEquals(3, loop.pointIds.distinct().size)
        assertEquals(listOf(360, 345), listOf(loop.paceAt(0), loop.paceAt(1)))
        assertEquals(loop, codec.decodeFromString<Course>(codec.encodeToString(loop)))
    }

    @Test fun `unassigned positive length makes whole estimate unknown`() {
        val partial = course().withPace(1, null).paceEstimate()
        assertFalse(partial.complete); assertNull(partial.seconds); assertNull(partial.averagePace)
        assertEquals(400.0, partial.assignedMeters, .001)
        assertEquals(162.0, partial.assignedSeconds, .001)
    }

    @Test fun `complete targets use distance weighted average`() {
        val estimate = course().paceEstimate()
        assertEquals(231.0, estimate.seconds!!, .001)
        assertEquals(385.0, estimate.averagePace!!, .001)
    }

    @Test fun `zero length adjacent edges add no expected time`() {
        val zero = Course(name = "중복 좌표", points = listOf(coordinate(0.0), coordinate(0.0), coordinate(100.0))).withPace(1, 360)
        assertTrue(zero.paceEstimate().complete)
        assertEquals(36.0, zero.paceEstimate().seconds!!, .001)
    }

    @Test fun `saved plan import applies goals in edge order without rewriting the plan`() {
        val plan = WorkoutPlan(name = "계획", steps = listOf(PlanStep(kind = StepKind.TIME, amount = 30.0, paceSeconds = 360),
            PlanStep(kind = StepKind.DISTANCE, amount = 5000.0, paceSeconds = 345)))
        val original = codec.encodeToString(plan)
        val connected = course().withPlanPaces(plan)
        assertEquals(360, connected.paceAt(0)); assertEquals(345, connected.paceAt(1)); assertNull(connected.paceAt(2))
        assertEquals(original, codec.encodeToString(plan))
        assertEquals(course().meters, connected.meters)
    }

    @Test fun `repeated assignments replace one edge instead of overlapping ranges`() {
        val next = course().withPace(0, 400).withPace(0, 420)
        assertEquals(3, next.paceTargets.size); assertEquals(420, next.paceAt(0))
        assertFailsWith<IllegalArgumentException> { next.withPace(0, 119) }
        assertFailsWith<IllegalArgumentException> { next.withPace(0, 1801) }
    }

    @Test fun `all edge assignment and clearing are explicit`() {
        val assigned = course().withAllPaces(390)
        assertEquals(listOf(390, 390, 390), (0..2).map(assigned::paceAt))
        val cleared = assigned.withAllPaces(null)
        assertTrue(cleared.usePaceTargets); assertTrue(cleared.paceTargets.isEmpty())
        assertNull(cleared.paceEstimate().seconds)
    }

    @Test fun `invalid ids and nonadjacent duplicate targets are rejected`() {
        val value = course()
        assertFailsWith<IllegalArgumentException> { value.copy(pointIds = listOf("a")) }
        assertFailsWith<IllegalArgumentException> { value.copy(pointIds = listOf("a", "a", "c", "d")) }
        assertFailsWith<IllegalArgumentException> { value.copy(paceTargets = listOf(CoursePaceTarget("a", "c", 360))) }
        assertFailsWith<IllegalArgumentException> { value.copy(paceTargets = value.paceTargets + value.paceTargets.first()) }
    }

    @Test fun `legacy course and version one exchange decode stable ids repeatedly`() {
        val text = """{"schemaVersion":1,"course":{"id":"old","name":"기존","points":[{"lat":0.0,"lon":0.0},{"lat":0.001,"lon":0.0}]}}"""
        val a = readCourseFile(text); val b = readCourseFile(text)
        assertEquals(a.pointIds, b.pointIds); assertFalse(a.usePaceTargets); assertTrue(a.paceTargets.isEmpty())
        assertEquals(2, a.pointIds.distinct().size)
        assertFalse(codec.encodeToString(a).contains("paceByStart"))
    }

    @Test fun `version two exchange preserves bindings and rejects unsupported versions`() {
        val file = CourseFile(course = course().copy(paceTransition = CoursePaceTransition.DISTANCE))
        val text = codec.encodeToString(file)
        assertEquals(2, file.schemaVersion); assertEquals(file.course, readCourseFile(text))
        assertFailsWith<IllegalArgumentException> { readCourseFile(text.replace("\"schemaVersion\":2", "\"schemaVersion\":3")) }
        val imported = readCourseFile(text).copy(id = newId())
        assertEquals(file.course.paceTargets, imported.paceTargets)
    }

    @Test fun `legacy plan and run keep independent targets and progress`() {
        val raw = """{"schemaVersion":1,"run":{"id":"old-run","startedAt":1,"planIndex":1,"course":{"id":"old","name":"기존","points":[{"lat":0.0,"lon":0.0},{"lat":0.001,"lon":0.0}]},"plan":{"id":"old-plan","name":"계획","steps":[{"kind":"DISTANCE","amount":100.0,"paceSeconds":360},{"kind":"TIME","amount":30.0,"paceSeconds":345}]}},"points":[],"hearts":[]}"""
        val file = readRunFile(raw)
        assertFalse(file.run.coursePaceEnabled); assertEquals(345, file.run.targetPace)
        assertEquals(1, file.run.planIndex)
        val encoded = codec.encodeToString(RunFile(run = file.run, points = file.points, hearts = file.hearts))
        assertEquals(file.run, readRunFile(encoded).run)
    }

    @Test fun `record snapshot and editor cancel retain original course and targets`() {
        val original = course()
        val run = RunSummary(startedAt = 1, course = original, coursePaceEnabled = true, courseLegIndex = 0)
        val draft = CourseEditorDraft(original).remove("b")
        val restoredDraft = codec.decodeFromString<CourseEditorDraft>(codec.encodeToString(draft))
        assertEquals(draft, restoredDraft); assertTrue(draft.dirty)
        assertEquals(original, draft.original); assertEquals(original, run.course)
        assertEquals(360, run.targetPace)
        val file = RunFile(run = run, points = emptyList(), hearts = emptyList())
        assertEquals(file, readRunFile(codec.encodeToString(file)))
    }
}
