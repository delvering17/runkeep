package com.runkeep.app.data

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import com.runkeep.core.RunSummary
import com.runkeep.core.healthimport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

class HealthImport(context: Context) {
    private val source = HealthConnectReadAdapter(context.applicationContext)

    suspend fun candidates(run: RunSummary): HealthResult<HealthCandidates> = operation(run, HealthStage.SESSION_READ) {
        HealthImportReader(source).candidates(it)
    }

    suspend fun samples(run: RunSummary, session: HealthSession?): HealthResult<HealthImportData> = operation(run, HealthStage.HEART_READ) {
        HealthImportReader(source).samples(it, session)
    }

    suspend fun diagnose(run: RunSummary, session: HealthSession?): HealthResult<HealthDiagnostics> = operation(run, HealthStage.SOURCE_COMPARE) {
        HealthImportReader(source).diagnose(it, session)
    }

    private suspend fun <T> operation(run: RunSummary, stage: HealthStage, read: suspend (HealthWindow) -> HealthResult<T>): HealthResult<T> {
        val end = run.endedAt
        if (end == null || end <= run.startedAt) return HealthResult.Failure(HealthFailure.INVALID_TIME, HealthStage.TIME_RANGE)
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(30_000L) { read(HealthWindow(Instant.ofEpochMilli(run.startedAt), Instant.ofEpochMilli(end))) }
                ?: HealthResult.Failure(HealthFailure.TIMEOUT, stage)
        }
    }

    companion object {
        val permissions = setOf(HealthPermission.getReadPermission(HeartRateRecord::class), HealthPermission.getReadPermission(ExerciseSessionRecord::class))
    }
}
