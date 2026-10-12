package com.runkeep.app.data

import android.content.Context
import androidx.room.*
import com.runkeep.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

@Entity(tableName = "courses") data class CourseRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "plans") data class PlanRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "runs") data class RunRow(@PrimaryKey val id: String, val startedAt: Long, val status: String, val payload: String)
@Entity(tableName = "track_points", primaryKeys = ["runId", "sequence"], indices = [Index("runId")])
data class PointRow(val runId: String, val sequence: Int, val payload: String)
@Entity(tableName = "heart_samples", primaryKeys = ["runId", "sourceId", "epochMs"], indices = [Index("runId")])
data class HeartRow(val runId: String, val sourceId: String, val epochMs: Long, val bpm: Long)

@Dao abstract class RunDao {
    @Query("SELECT * FROM courses ORDER BY rowid DESC") abstract fun courses(): Flow<List<CourseRow>>
    @Query("SELECT * FROM plans") abstract fun plans(): Flow<List<PlanRow>>
    @Query("SELECT * FROM runs ORDER BY startedAt DESC") abstract fun runs(): Flow<List<RunRow>>
    @Query("SELECT * FROM runs WHERE id=:id") abstract suspend fun run(id: String): RunRow?
    @Query("SELECT * FROM runs WHERE status != 'FINISHED' ORDER BY startedAt DESC LIMIT 1") abstract suspend fun unfinished(): RunRow?
    @Query("SELECT * FROM track_points WHERE runId=:id ORDER BY sequence") abstract suspend fun points(id: String): List<PointRow>
    @Query("SELECT * FROM heart_samples WHERE runId=:id ORDER BY epochMs") abstract fun hearts(id: String): Flow<List<HeartRow>>
    @Query("SELECT DISTINCT runId FROM heart_samples") abstract fun heartRunIds(): Flow<List<String>>
    @Upsert abstract suspend fun saveCourse(row: CourseRow)
    @Upsert abstract suspend fun savePlan(row: PlanRow)
    @Upsert abstract suspend fun saveRun(row: RunRow)
    @Upsert abstract suspend fun savePoint(row: PointRow)
    @Upsert abstract suspend fun saveHearts(rows: List<HeartRow>)
    @Query("DELETE FROM courses WHERE id=:id") abstract suspend fun deleteCourse(id: String)
    @Query("DELETE FROM plans WHERE id=:id") abstract suspend fun deletePlan(id: String)
    @Query("DELETE FROM runs WHERE id=:id") abstract suspend fun deleteRunRow(id: String)
    @Query("DELETE FROM track_points WHERE runId=:id") abstract suspend fun deletePoints(id: String)
    @Query("DELETE FROM heart_samples WHERE runId=:id") abstract suspend fun deleteHearts(id: String)
    @Transaction open suspend fun deleteRun(id: String) { deletePoints(id); deleteHearts(id); deleteRunRow(id) }
    @Transaction open suspend fun checkpoint(run: RunSummary, point: TrackPoint? = null, sequence: Int = 0) {
        saveRun(RunRow(run.id, run.startedAt, run.status.name, codec.encodeToString(run)))
        if (point != null) savePoint(PointRow(run.id, sequence, codec.encodeToString(point)))
    }
}
@Database(entities = [CourseRow::class, PlanRow::class, RunRow::class, PointRow::class, HeartRow::class], version = 1, exportSchema = true)
abstract class RunDatabase : RoomDatabase() {
    abstract fun dao(): RunDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, RunDatabase::class.java, "runkeep.db").build()
    }
}
