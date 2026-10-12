package com.runkeep.core

data class RecordPresentation(val run: RunSummary, val points: List<TrackPoint>, val heartUntil: Long?, val goalOnly: Boolean) {
    companion object {
        fun from(source: RunSummary, points: List<TrackPoint>, includeExtra: Boolean): RecordPresentation {
            val achievedGoal = source.goalActiveMs != null && source.goalMeters != null
            val goalOnly = achievedGoal && !includeExtra
            val displayed = when {
                goalOnly -> source.copy(meters = requireNotNull(source.goalMeters), activeMs = requireNotNull(source.goalActiveMs))
                achievedGoal -> source.copy(goalMeters = null, goalActiveMs = null, goalEpochMs = null)
                else -> source
            }
            return RecordPresentation(displayed, if (goalOnly) Reports.clip(points, displayed.meters) else points,
                if (goalOnly) source.goalEpochMs ?: source.endedAt else source.endedAt, goalOnly)
        }
    }
}
