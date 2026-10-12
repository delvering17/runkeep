package com.runkeep.core

import kotlinx.serialization.Serializable

@Serializable enum class CoursePaceTransition { WAYPOINT, DISTANCE }

@Serializable data class CoursePaceTarget(val fromPointId: String, val toPointId: String, val paceSeconds: Int) {
    init { require(paceSeconds in 120..1800) }
}

fun Course.paceAt(index: Int): Int? {
    val from = pointIds.getOrNull(index) ?: return null
    val to = pointIds.getOrNull(index + 1) ?: return null
    return paceByStart[from]?.takeIf { it.toPointId == to }?.paceSeconds
}

fun Course.withPace(index: Int, paceSeconds: Int?): Course {
    if (index !in 0 until points.lastIndex) return this
    val from = pointIds[index]; val to = pointIds[index + 1]
    val kept = paceTargets.filterNot { it.fromPointId == from && it.toPointId == to }
    return copy(paceTargets = kept + listOfNotNull(paceSeconds?.let { CoursePaceTarget(from, to, it) }), usePaceTargets = true)
}

fun Course.withPlanPaces(plan: WorkoutPlan): Course = copy(usePaceTargets = true, paceTargets = pointIds.zipWithNext().mapIndexedNotNull { index, (from, to) ->
    plan.steps.getOrNull(index)?.let { CoursePaceTarget(from, to, it.paceSeconds) }
})

fun Course.withAllPaces(paceSeconds: Int?): Course = copy(usePaceTargets = true,
    paceTargets = pointIds.zipWithNext().mapNotNull { (from, to) -> paceSeconds?.let { CoursePaceTarget(from, to, it) } })

data class CoursePaceEstimate(val totalMeters: Double, val assignedMeters: Double, val assignedSeconds: Double, val complete: Boolean) {
    val seconds get() = assignedSeconds.takeIf { complete && totalMeters > 0 }
    val averagePace get() = seconds?.div(totalMeters)?.times(1000)
}

fun Course.paceEstimate(): CoursePaceEstimate {
    var assignedMeters = 0.0; var seconds = 0.0; var complete = true
    points.zipWithNext().forEachIndexed { index, (a, b) ->
        val meters = Geo.distance(a, b)
        val pace = paceAt(index)
        if (meters > .01 && pace == null) complete = false
        if (pace != null) { assignedMeters += meters; seconds += meters / 1000 * pace }
    }
    return CoursePaceEstimate(meters, assignedMeters, seconds, complete)
}

@Serializable data class CourseEditorDraft(val original: Course, val course: Course = original) {
    val dirty get() = course != original
    fun insert(index: Int, point: Coordinate): CourseEditorDraft {
        if (index !in 0..course.points.size || course.points.size >= 10_000) return this
        return replace(course.points.toMutableList().apply { add(index, point) }, course.pointIds.toMutableList().apply { add(index, newId()) })
    }
    fun remove(id: String): CourseEditorDraft {
        val index = course.pointIds.indexOf(id)
        if (index < 0) return this
        return replace(course.points.toMutableList().apply { removeAt(index) }, course.pointIds.toMutableList().apply { removeAt(index) })
    }
    fun relocate(id: String, point: Coordinate): CourseEditorDraft {
        val index = course.pointIds.indexOf(id)
        if (index < 0) return this
        return copy(course = course.copy(points = course.points.toMutableList().apply { this[index] = point }))
    }
    fun move(id: String, destination: Int, paceTargets: List<CoursePaceTarget> = course.paceTargets): CourseEditorDraft {
        val index = course.pointIds.indexOf(id)
        if (index < 0 || destination !in course.points.indices) return this
        return replace(course.points.toMutableList().apply { add(destination, removeAt(index)) },
            course.pointIds.toMutableList().apply { add(destination, removeAt(index)) }, paceTargets)
    }
    private fun replace(points: List<Coordinate>, ids: List<String>, paceTargets: List<CoursePaceTarget> = course.paceTargets): CourseEditorDraft {
        val adjacent = ids.zipWithNext().toSet()
        return copy(course = course.copy(points = points, pointIds = ids,
            paceTargets = paceTargets.filter { it.fromPointId to it.toPointId in adjacent }))
    }
}

const val MAX_COURSE_FILE_BYTES = 8_000_000
const val MAX_LEGACY_COURSE_FILE_BYTES = 2_000_000

fun readCourseFile(text: String): Course {
    val bytes = text.toByteArray(Charsets.UTF_8).size
    require(bytes <= MAX_COURSE_FILE_BYTES) { "코스 JSON은 8MB 이하여야 합니다" }
    val file = codec.decodeFromString<CourseFile>(text)
    require(file.schemaVersion in 1..2) { "지원하지 않는 코스 버전입니다" }
    require(file.schemaVersion != 1 || bytes <= MAX_LEGACY_COURSE_FILE_BYTES) { "기존 버전 코스 JSON은 2MB 이하여야 합니다" }
    require(file.course.points.size in 2..10_000) { "코스에는 2~10,000개 지점이 필요합니다" }
    return file.course
}

fun readRunFile(text: String): RunFile {
    val file = codec.decodeFromString<RunFile>(text)
    require(file.schemaVersion in 1..2) { "지원하지 않는 기록 버전입니다" }
    return file
}
