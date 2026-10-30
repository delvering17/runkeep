package com.runkeep.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseListPolicyTest {
    private fun course(id: String, length: Double = .01, name: String = id): Course =
        Course(id = id, name = name, points = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, length)))

    private fun run(id: String, course: Course?, status: RunStatus = RunStatus.FINISHED): RunSummary =
        RunSummary(id = id, startedAt = 1, course = course, status = status)

    private fun ids(entries: List<CourseListEntry>, sort: CourseListSort, ranOnly: Boolean = false): List<String> =
        visibleCourseEntries(entries, sort, ranOnly).map { it.course.id }

    @Test fun `all seven sorts use registration distance target and linked finished counts`() {
        val latest = course("latest", .01).withAllPaces(480)
        val middle = course("middle", .02).withAllPaces(240)
        val oldest = course("oldest", .03).withAllPaces(360)
        val entries = courseListEntries(listOf(latest, middle, oldest), listOf(
            run("a", latest), run("b", middle), run("c", middle),
            run("d", oldest), run("e", oldest), run("f", oldest)))
        assertEquals(listOf("latest", "middle", "oldest"), ids(entries, CourseListSort.REGISTERED))
        assertEquals(listOf("oldest", "middle", "latest"), ids(entries, CourseListSort.DISTANCE_LONG))
        assertEquals(listOf("latest", "middle", "oldest"), ids(entries, CourseListSort.DISTANCE_SHORT))
        assertEquals(listOf("middle", "oldest", "latest"), ids(entries, CourseListSort.TARGET_FAST))
        assertEquals(listOf("latest", "oldest", "middle"), ids(entries, CourseListSort.TARGET_SLOW))
        assertEquals(listOf("oldest", "middle", "latest"), ids(entries, CourseListSort.RUNS_MANY))
        assertEquals(listOf("latest", "middle", "oldest"), ids(entries, CourseListSort.RUNS_FEW))
    }

    @Test fun `numeric ties use immutable course IDs regardless of input order or rename`() {
        val a = course("a", name = "긴 이름 코스").withAllPaces(360)
        val b = course("b", name = "같은 이름").withAllPaces(360)
        val numericSorts = CourseListSort.entries.filterNot { it == CourseListSort.REGISTERED }
        for (sort in numericSorts) {
            assertEquals(listOf("a", "b"), ids(courseListEntries(listOf(b, a), emptyList()), sort))
            assertEquals(listOf("a", "b"), ids(courseListEntries(listOf(a.copy(name = "새 이름"), b), emptyList()), sort))
        }
        val registrationTie = courseListEntries(listOf(b, a), emptyList()).map { it.copy(registrationIndex = 0) }
        assertEquals(listOf("a", "b"), ids(registrationTie, CourseListSort.REGISTERED))
        // Registration deliberately follows the explicit DAO insertion order rather than the ID.
        assertEquals(listOf("b", "a"), ids(courseListEntries(listOf(b, a), emptyList()), CourseListSort.REGISTERED))
    }

    @Test fun `unset partial disabled and zero distance targets remain last in either pace direction`() {
        val fast = course("fast").withAllPaces(240)
        val slow = course("slow").withAllPaces(480)
        val unset = course("unset")
        val partial = Course(id = "partial", name = "부분 목표", points = listOf(
            Coordinate(0.0, 0.0), Coordinate(0.0, .01), Coordinate(0.0, .02))).withPace(0, 360)
        val disabled = course("disabled").withAllPaces(120).copy(usePaceTargets = false)
        val zero = course("zero", 0.0).withAllPaces(120)
        val entries = courseListEntries(listOf(unset, partial, slow, disabled, fast, zero), emptyList())
        assertEquals(listOf("fast", "slow", "disabled", "partial", "unset", "zero"), ids(entries, CourseListSort.TARGET_FAST))
        assertEquals(listOf("slow", "fast", "disabled", "partial", "unset", "zero"), ids(entries, CourseListSort.TARGET_SLOW))
        assertTrue(entries.single { it.course.id == "partial" }.partialTarget)
        assertNull(entries.single { it.course.id == "partial" }.targetPace)
        assertFalse(entries.single { it.course.id == "disabled" }.partialTarget)
    }

    @Test fun `target average weights segment distance rather than count or running performance`() {
        val weighted = Course(id = "weighted", name = "거리 가중", points = listOf(
            Coordinate(0.0, 0.0), Coordinate(0.0, .01), Coordinate(0.0, .04)))
            .withPace(0, 240).withPace(1, 480)
        val uniform = course("uniform").withAllPaces(390)
        val completed = run("performance", weighted).copy(activeMs = 1, meters = 10_000.0)
        val entries = courseListEntries(listOf(weighted, uniform), listOf(completed))
        assertEquals(420.0, entries.single { it.course.id == "weighted" }.targetPace!!, .0001)
        assertEquals(listOf("uniform", "weighted"), ids(entries, CourseListSort.TARGET_FAST))
    }

    @Test fun `finished snapshot ID survives rename and never matches same names or deleted IDs`() {
        val original = course("kept", name = "원래 이름")
        val renamed = original.copy(name = "새 이름")
        val sameName = course("other", name = "원래 이름")
        val removed = course("deleted", name = "새 이름")
        val runs = listOf(run("finished", original), run("deleted", removed),
            run("legacy", null), run("active", original, RunStatus.RUNNING),
            run("paused", original, RunStatus.PAUSED), run("interrupted", original, RunStatus.INTERRUPTED))
        assertEquals(mapOf("kept" to 1), courseRunCounts(listOf(renamed, sameName), runs))
        assertEquals("원래 이름", runs.first().course?.name)
    }

    @Test fun `legacy records without a course stay unlinked and duplicate observations count once`() {
        val course = course("course", name = "이전 코스")
        val legacy = codec.decodeFromString<RunSummary>("""{"id":"old","startedAt":1,"status":"FINISHED"}""")
        val finished = run("same", course)
        assertNull(legacy.course)
        assertEquals(mapOf("course" to 1), courseRunCounts(listOf(course), listOf(legacy, finished, finished)))
    }

    @Test fun `default ran filter empty results can be turned off without changing courses`() {
        val course = course("new")
        val entries = courseListEntries(listOf(course), emptyList())
        for (sort in CourseListSort.entries) {
            assertTrue(visibleCourseEntries(entries, sort).isEmpty())
            assertEquals(listOf("new"), ids(entries, sort, ranOnly = false))
            assertTrue(visibleCourseEntries(emptyList(), sort, ranOnly = false).isEmpty())
        }
        assertEquals(course, entries.single().course)
    }

    @Test fun `run count filter combines with every sort and tracks deleted records`() {
        val a = course("a"); val b = course("b"); val c = course("c")
        val runs = listOf(run("a-run", a), run("b-run", b))
        val entries = courseListEntries(listOf(c, b, a), runs)
        for (sort in CourseListSort.entries) {
            assertEquals(setOf("a", "b"), visibleCourseEntries(entries, sort).map { it.course.id }.toSet())
        }
        assertEquals(listOf("b"), ids(courseListEntries(listOf(a, b, c), runs.drop(1)), CourseListSort.RUNS_MANY, ranOnly = true))
    }
}
