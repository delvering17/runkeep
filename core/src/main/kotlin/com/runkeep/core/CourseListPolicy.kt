package com.runkeep.core

/** Registration order is supplied by the DAO, newest inserted row first. */
enum class CourseListSort {
    REGISTERED, DISTANCE_LONG, DISTANCE_SHORT, TARGET_FAST, TARGET_SLOW, RUNS_MANY, RUNS_FEW
}

data class CourseListEntry(
    val course: Course,
    val meters: Double,
    val targetPace: Double?,
    val partialTarget: Boolean,
    val runCount: Int,
    val registrationIndex: Int
)

/** Finished record snapshots retain the original course ID even after rename/edit/delete. */
fun courseRunCounts(courses: List<Course>, runs: List<RunSummary>): Map<String, Int> {
    val ids = courses.map { it.id }.filter { it.isNotBlank() }.toSet()
    return runs.asSequence().filter { it.status == RunStatus.FINISHED }.distinctBy { it.id }
        .mapNotNull { it.course?.id?.takeIf(ids::contains) }.groupingBy { it }.eachCount()
}

fun courseListEntries(courses: List<Course>, runs: List<RunSummary>): List<CourseListEntry> {
    val counts = courseRunCounts(courses, runs)
    return courses.mapIndexed { index, course ->
        val estimate = course.paceEstimate()
        CourseListEntry(course, estimate.totalMeters,
            estimate.averagePace.takeIf { course.usePaceTargets },
            course.usePaceTargets && !estimate.complete && estimate.assignedMeters > 0,
            counts[course.id] ?: 0, index)
    }
}

/** Unset/partial targets stay last in either direction; every numeric tie uses the ID. */
fun visibleCourseEntries(
    entries: List<CourseListEntry>,
    sort: CourseListSort = CourseListSort.REGISTERED,
    ranOnly: Boolean = true
): List<CourseListEntry> {
    val comparator = when (sort) {
        CourseListSort.REGISTERED -> compareBy<CourseListEntry> { it.registrationIndex }
        CourseListSort.DISTANCE_LONG -> compareByDescending<CourseListEntry> { it.meters }
        CourseListSort.DISTANCE_SHORT -> compareBy<CourseListEntry> { it.meters }
        CourseListSort.TARGET_FAST, CourseListSort.TARGET_SLOW ->
            compareBy<CourseListEntry> { it.targetPace == null }.thenComparator { a, b ->
                val left = a.targetPace; val right = b.targetPace
                if (left == null || right == null) 0
                else if (sort == CourseListSort.TARGET_FAST) left.compareTo(right) else right.compareTo(left)
            }
        CourseListSort.RUNS_MANY -> compareByDescending<CourseListEntry> { it.runCount }
        CourseListSort.RUNS_FEW -> compareBy<CourseListEntry> { it.runCount }
    }.thenBy { it.course.id }
    return entries.filter { !ranOnly || it.runCount > 0 }.sortedWith(comparator)
}
