package com.runkeep.app.ui

import com.runkeep.core.*

/** UI selections form a start snapshot; service start/countdown/recording policies stay separate. */
data class RunPreparationSelection(
    val course: Course?,
    val plan: WorkoutPlan?,
    val targetMeters: Double?,
    val steadyPaceSeconds: Double?
) {
    val coursePace = course?.usePaceTargets == true
    val goalMeters = if (coursePace) course?.meters?.takeIf { MeasurementLimits.distance(it, 100.0) }
        else if (plan != null) plan.meters.takeIf { plan.steps.all { it.kind == StepKind.DISTANCE } } else targetMeters
    private val paceSeconds = steadyPaceSeconds?.toInt()?.takeIf(MeasurementLimits::pace)
    val valid = if (coursePace) course?.let { it.points.size >= 2 && it.meters > .01 } == true
        else (plan != null || goalMeters == null || MeasurementLimits.distance(goalMeters, 100.0)) &&
            (steadyPaceSeconds == null || plan != null || paceSeconds != null)

    fun startRequest(weightKg: Double?): RunSummary {
        val chosenPlan = plan ?: if (!coursePace && goalMeters != null && paceSeconds != null)
            WorkoutPlan(name = "일정 페이스", steps = listOf(PlanStep(kind = StepKind.DISTANCE,
                amount = requireNotNull(goalMeters), paceSeconds = requireNotNull(paceSeconds)))) else null
        return RunSummary(startedAt = 0, goalMeters = goalMeters, course = course, plan = chosenPlan,
            weightKg = weightKg, coursePaceEnabled = coursePace)
    }
}

/** User names stay literal; only the distance number is interpreted as a format string. */
fun runPreparationCourseLabel(course: Course): String {
    val distance = "%.2f km".format(course.meters / 1000)
    val target = course.takeIf { it.usePaceTargets }?.paceEstimate()?.averagePace
        ?.let { "목표 ${paceText(it)}/km" }
    return listOfNotNull("${course.name} · $distance", target).joinToString(" · ")
}
