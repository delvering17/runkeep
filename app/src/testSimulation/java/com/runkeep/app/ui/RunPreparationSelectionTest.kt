package com.runkeep.app.ui

import com.runkeep.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunPreparationSelectionTest {
    private fun course() = Course(id = "course", name = "왕복", points = listOf(
        Coordinate(37.0, 127.0), Coordinate(37.001, 127.0), Coordinate(37.0, 127.0)),
        pointIds = listOf("start", "turn", "finish"))

    @Test fun `코스와 계획 없는 시작은 목표를 만들지 않는다`() {
        val selected = RunPreparationSelection(null, null, null, null)
        val request = selected.startRequest(null)
        assertTrue(selected.valid)
        assertNull(request.goalMeters)
        assertNull(request.plan)
        assertNull(request.course)
        assertFalse(request.coursePaceEnabled)
        assertEquals(0L, request.startedAt)
    }

    @Test fun `일정 페이스 시작은 소수 거리와 정확한 초를 보존한다`() {
        val selected = RunPreparationSelection(null, null, 21097.5, 397.0)
        val request = selected.startRequest(67.5)
        assertTrue(selected.valid)
        assertEquals(21097.5, request.goalMeters)
        assertEquals(21097.5, request.plan!!.steps.single().amount)
        assertEquals(397, request.plan!!.steps.single().paceSeconds)
        assertEquals(67.5, request.weightKg)
        assertFalse(request.coursePaceEnabled)
    }

    @Test fun `저장 계획은 숨겨진 수동 목표보다 우선하고 시간 계획은 목표거리를 고정하지 않는다`() {
        val mixed = WorkoutPlan(name = "혼합", steps = listOf(
            PlanStep(kind = StepKind.DISTANCE, amount = 1000.0, paceSeconds = 360),
            PlanStep(kind = StepKind.TIME, amount = 60.0, paceSeconds = 397)))
        val selected = RunPreparationSelection(null, mixed, 21097.5, 1.0)
        assertTrue(selected.valid)
        assertNull(selected.goalMeters)
        assertEquals(mixed, selected.startRequest(null).plan)
        val distance = mixed.copy(steps = mixed.steps.take(1))
        assertEquals(1000.0, RunPreparationSelection(null, distance, 21097.5, 397.0).goalMeters)
    }

    @Test fun `코스 목표 연결과 전환방식은 출발 사본에 유지된다`() {
        val original = course()
        val planned = WorkoutPlan(name = "페이스", steps = listOf(
            PlanStep(kind = StepKind.TIME, amount = 60.0, paceSeconds = 397)))
        val configured = original.withPlanPaces(planned).copy(paceTransition = CoursePaceTransition.DISTANCE)
        val selected = RunPreparationSelection(configured, planned, 21097.5, 1.0)
        val request = selected.startRequest(null)
        assertTrue(selected.valid)
        assertTrue(request.coursePaceEnabled)
        assertEquals(configured.meters, request.goalMeters)
        assertEquals(configured, request.course)
        assertEquals(planned, request.plan)
        assertEquals(397, request.course!!.paceAt(0))
        assertNull(request.course!!.paceAt(1))
        assertEquals(CoursePaceTransition.DISTANCE, request.course!!.paceTransition)
        assertTrue(original.paceTargets.isEmpty())
    }

    @Test fun `코스만 전환은 연결 값을 남기고 코스 페이스 실행을 끈다`() {
        val configured = course().withAllPaces(397)
        val selected = RunPreparationSelection(configured.copy(usePaceTargets = false), null, null, null)
        val request = selected.startRequest(null)
        assertTrue(selected.valid)
        assertFalse(request.coursePaceEnabled)
        assertEquals(configured.paceTargets, request.course!!.paceTargets)
        assertNull(request.goalMeters)
        assertNull(request.plan)
    }

    @Test fun `한 값 입력의 범위와 짧은 코스 목표 정책을 보존한다`() {
        assertFalse(RunPreparationSelection(null, null, 99.0, null).valid)
        assertFalse(RunPreparationSelection(null, null, 200001.0, null).valid)
        assertFalse(RunPreparationSelection(null, null, 1000.0, 119.0).valid)
        assertTrue(RunPreparationSelection(null, null, 100.0, 1800.0).valid)
        val short = Course(name = "짧은 코스", points = listOf(Coordinate(37.0, 127.0), Coordinate(37.00001, 127.0))).withAllPaces(397)
        val selected = RunPreparationSelection(short, null, 21097.5, 397.0)
        assertTrue(selected.valid)
        assertNull(selected.goalMeters)
        assertTrue(selected.startRequest(null).coursePaceEnabled)
    }

    @Test fun `퍼센트와 포맷 문자가 든 코스 이름을 그대로 표시한다`() {
        val base = course()
        val distance = "%.2f km".format(base.meters / 1000)
        listOf("100%", "%s 코스", "%n 줄바꿈 없음", "%.2f 경로", "%%", "%1\$s").forEach { name ->
            assertEquals("$name · $distance", runPreparationCourseLabel(base.copy(name = name)))
        }
        assertEquals("100% · $distance · 목표 6:37/km",
            runPreparationCourseLabel(base.copy(name = "100%").withAllPaces(397)))
    }

}
