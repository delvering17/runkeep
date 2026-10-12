package com.runkeep.core

import kotlinx.serialization.Serializable

@Serializable data class PlanEditorRow(val id: String = newId(), val step: PlanStep)

@Serializable data class PlanEditorDraft(val original: WorkoutPlan, val name: String = original.name,
                                        val rows: List<PlanEditorRow> = original.steps.map { PlanEditorRow(step = it) }) {
    val plan get() = original.copy(name = name, steps = rows.map { it.step })
    val dirty get() = plan != original
    val valid get() = name.isNotBlank() && rows.isNotEmpty() && rows.size <= MAX_ROWS &&
        rows.all { it.step.name.isNotBlank() && MeasurementLimits.step(it.step) } && rows.map { it.id }.distinct().size == rows.size

    fun edit(id: String, step: PlanStep) = copy(rows = rows.map { if (it.id == id) it.copy(step = step) else it })
    fun append(step: PlanStep = PlanStep(kind = StepKind.DISTANCE, amount = 1000.0, paceSeconds = 390)): PlanEditorDraft =
        if (rows.size >= MAX_ROWS) this else copy(rows = rows + PlanEditorRow(step = step))
    fun remove(id: String) = copy(rows = rows.filterNot { it.id == id })
    fun clone(id: String): PlanEditorDraft {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || rows.size >= MAX_ROWS) return this
        return copy(rows = rows.toMutableList().apply { add(index + 1, rows[index].copy(id = newId())) })
    }
    fun move(id: String, target: Int): PlanEditorDraft {
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0 || target !in rows.indices || from == target) return this
        return copy(rows = rows.toMutableList().apply { add(target, removeAt(from)) })
    }
    fun repeat(count: Int): PlanEditorDraft {
        if (count !in 2..20 || rows.isEmpty() || rows.size > MAX_ROWS / count) return this
        return copy(rows = rows + List(count - 1) { rows.map { it.copy(id = newId()) } }.flatten())
    }
    companion object { const val MAX_ROWS = 100 }
}
