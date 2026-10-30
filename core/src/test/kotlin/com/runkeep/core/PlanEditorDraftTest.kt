package com.runkeep.core

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanEditorDraftTest {
    private val distance = PlanStep("빠르게", StepKind.DISTANCE, 21097.5, 300)
    private val time = PlanStep("회복", StepKind.TIME, 600.5, 420)
    private fun initial() = PlanEditorDraft(WorkoutPlan(id = "saved", name = "훈련", steps = listOf(distance, time, distance)))

    @Test fun `moving identical steps uses stable row identity and preserves mixed totals`() {
        val original = initial()
        val id = original.rows.first().id
        val moved = original.move(id, 2)
        assertEquals(listOf(original.rows[1], original.rows[2], original.rows[0]), moved.rows)
        assertEquals(original.plan.meters, moved.plan.meters, 0.0000001)
        assertEquals(original.plan.seconds, moved.plan.seconds, 0.0000001)
        assertEquals(original.plan.averagePace!!, moved.plan.averagePace!!, 0.0000001)
        assertEquals(original, moved.move(id, 0))
        assertSame(original, original.move(id, -1))
        assertSame(original, original.move(id, 3))
        assertSame(original, original.move("missing", 1))
    }

    @Test fun `individual edits retain identity and recompute time distance and weighted pace`() {
        val original = initial()
        val row = original.rows[1]
        val changed = original.edit(row.id, row.step.copy(name = "回復", amount = 1200.5, paceSeconds = 390))
        assertEquals(row.id, changed.rows[1].id)
        assertEquals(1200.5 / 390 * 1000, changed.rows[1].step.expectedMeters, 0.0000001)
        val meters = distance.amount * 2 + 1200.5 / 390 * 1000
        val seconds = distance.expectedSeconds * 2 + 1200.5
        assertEquals(meters, changed.plan.meters, 0.0000001)
        assertEquals(seconds / meters * 1000, changed.plan.averagePace!!, 0.0000001)
        assertEquals(time, original.rows[1].step)
        assertTrue(changed.dirty)
    }

    @Test fun `clone inserts a fresh identity directly after selected row`() {
        val original = initial()
        val cloned = original.clone(original.rows[1].id)
        assertEquals(original.rows[1].step, cloned.rows[2].step)
        assertNotEquals(original.rows[1].id, cloned.rows[2].id)
        assertEquals(original.rows[2], cloned.rows[3])
        assertEquals(4, cloned.rows.map { it.id }.distinct().size)
        assertEquals(3, original.rows.size)
        assertSame(original, original.clone("missing"))
    }

    @Test fun `repeat preserves first pass identities and quantity units while generating all later identities`() {
        val original = initial()
        val repeated = original.repeat(20)
        assertEquals(original.rows, repeated.rows.take(3))
        assertEquals(List(20) { original.plan.steps }.flatten(), repeated.plan.steps)
        assertEquals(60, repeated.rows.map { it.id }.distinct().size)
        assertEquals(original.plan.meters * 20, repeated.plan.meters, 0.000001)
        assertEquals(original.plan.averagePace!!, repeated.plan.averagePace!!, 0.0000001)
        assertEquals(time.amount, repeated.rows[4].step.amount)
    }

    @Test fun `append clone and repeat enforce one hundred rows and two to twenty repetitions`() {
        val original = initial()
        val hundred = original.copy(rows = List(100) { PlanEditorRow(step = distance) })
        assertSame(hundred, hundred.append())
        assertSame(hundred, hundred.clone(hundred.rows[0].id))
        assertSame(hundred, hundred.repeat(2))
        listOf(Int.MIN_VALUE, 0, 1, 21, Int.MAX_VALUE).forEach { assertSame(original, original.repeat(it)) }
        val five = original.copy(rows = List(5) { PlanEditorRow(step = time) })
        assertEquals(100, five.repeat(20).rows.size)
        val six = original.copy(rows = List(6) { PlanEditorRow(step = time) })
        assertSame(six, six.repeat(20))
        assertEquals(100, hundred.remove(hundred.rows[0].id).append().rows.size)
    }

    @Test fun `structural undo snapshot restores every identity value and draft name`() {
        val original = initial().copy(name = "새 이름")
        val snapshot = codec.encodeToString(original)
        val removed = original.remove(original.rows[1].id)
        assertEquals(listOf(original.rows[0], original.rows[2]), removed.rows)
        val restored = codec.decodeFromString<PlanEditorDraft>(snapshot)
        assertEquals(original, restored)
        assertEquals(time, restored.rows[1].step)
    }

    @Test fun `serialized configuration draft restores edits and identities without adding ids to saved plan`() {
        val original = initial()
        val changed = original.append().move(original.rows[0].id, 3).copy(name = "변경")
        val restored = codec.decodeFromString<PlanEditorDraft>(codec.encodeToString(changed))
        assertEquals(changed, restored)
        assertTrue(restored.dirty)
        val saved = codec.encodeToString(restored.plan)
        restored.rows.forEach { assertFalse(saved.contains(it.id)) }
        assertEquals(restored.plan, codec.decodeFromString<WorkoutPlan>(saved))
    }

    @Test fun `cancel and edit back to original retain historical plan copies and clear dirty state`() {
        val original = initial()
        val historical = RunSummary(startedAt = 1, plan = original.plan)
        val edited = original.edit(original.rows[0].id, distance.copy(amount = 500.0)).copy(name = "수정")
        assertTrue(edited.dirty)
        assertEquals(original.plan, edited.original)
        assertEquals(original.plan, historical.plan)
        val reverted = edited.copy(name = original.name).edit(original.rows[0].id, distance)
        assertFalse(reverted.dirty)
        assertFalse(PlanEditorDraft(WorkoutPlan(name = "", steps = emptyList())).dirty)
    }

    @Test fun `save rejects invalid restored amount missing names duplicate identity and empty plans`() {
        val original = initial()
        assertTrue(original.valid)
        assertFalse(original.copy(name = " ").valid)
        assertFalse(original.copy(rows = emptyList()).valid)
        assertFalse(original.edit(original.rows[0].id, distance.copy(name = "")).valid)
        assertFalse(original.edit(original.rows[0].id, distance.copy(amount = 200001.0)).valid)
        assertFalse(original.edit(original.rows[1].id, time.copy(amount = 12000.1)).valid)
        assertFalse(original.copy(rows = listOf(original.rows[0], original.rows[0])).valid)
        assertTrue(original.edit(original.rows[1].id, time.copy(amount = 12000.0)).valid)
        assertTrue(original.edit(original.rows[0].id, distance.copy(amount = 200000.0)).valid)
    }
}
