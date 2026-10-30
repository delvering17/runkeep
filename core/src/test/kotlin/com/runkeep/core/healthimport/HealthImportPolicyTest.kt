package com.runkeep.core.healthimport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CancellationException
import kotlin.coroutines.*

class HealthImportPolicyTest {
    private fun at(ms: Long) = Instant.ofEpochMilli(ms)
    private fun window(start: Long = 1000, end: Long = 5000) = HealthWindow(at(start), at(end))
    private fun session(id: String = "session", start: Long = 2000, end: Long = 4000, origin: String = SAMSUNG_HEALTH_ORIGIN) =
        HealthSession(id, origin, window(start, end))
    private fun series(id: String = "record", origin: String = SAMSUNG_HEALTH_ORIGIN, start: Long = 500, end: Long = 6000,
        vararg samples: Long) = HealthSeries(id, origin, at(start), at(end), samples.map { HealthReading(at(it), 120) })

    private class Source : HealthImportSource {
        var status = HealthAvailability.AVAILABLE
        var permission = HealthReadAccess(true, true)
        var sessionPages = mapOf<String?, HealthPage<HealthSession>>(null to HealthPage(emptyList(), null))
        var heartPages = mapOf<String?, HealthPage<HealthSeries>>(null to HealthPage(emptyList(), null))
        val sessionTokens = mutableListOf<String?>()
        val queries = mutableListOf<Triple<HealthWindow, Boolean, String?>>()
        var failToken: String? = "never"
        var failReason = HealthFailure.READ_FAILED
        var cancellation = false
        override fun availability() = status
        override suspend fun access() = permission
        override suspend fun sessions(window: HealthWindow, token: String?): HealthPage<HealthSession> {
            sessionTokens += token
            return requireNotNull(sessionPages[token])
        }
        override suspend fun hearts(window: HealthWindow, samsungOnly: Boolean, token: String?): HealthPage<HealthSeries> {
            queries += Triple(window, samsungOnly, token)
            if (cancellation) throw CancellationException()
            if (token == failToken) throw HealthReadException(failReason)
            return requireNotNull(heartPages[token])
        }
    }

    private fun <T> run(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completion = result }
        })
        return requireNotNull(completion).getOrThrow()
    }
    private fun <T> success(result: HealthResult<T>) = (result as HealthResult.Success<T>).value

    @Test fun `selected intersection excludes run and session boundaries`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(1000, 1999, 2000, 3999, 4000, 4999))), null)) }
        val data = success(run { HealthImportReader(source).samples(window(), session()) })
        assertEquals(listOf(2000L, 3999L), data.samples.map { it.epochMs })
        assertEquals(window(2000, 4000), source.queries.single().first)
        assertTrue(source.queries.single().second)
    }

    @Test fun `record spanning the selected window retains its inner samples`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(500, 1000, 4999, 5000, 6000))), null)) }
        val data = success(run { HealthImportReader(source).samples(window(), null) })
        assertEquals(listOf(1000L, 4999L), data.samples.map { it.epochMs })
        assertEquals(1, data.diagnostics.samsung.overlappingRecords)
    }

    @Test fun `zero duration heart record and record end sample are accepted inside the query`() {
        val point = series(start = 2000, end = 2000, samples = longArrayOf(2000))
        val last = series(id = "last", start = 1000, end = 3000, samples = longArrayOf(3000))
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(point, last), null)) }
        assertEquals(listOf(2000L, 3000L), success(run { HealthImportReader(source).samples(window(), null) }).samples.map { it.epochMs })
    }

    @Test fun `samples outside record bounds or with invalid bpm are diagnosed without creating replacements`() {
        val record = HealthSeries("record", SAMSUNG_HEALTH_ORIGIN, at(2000), at(3000), listOf(
            HealthReading(at(1999), 120), HealthReading(at(3001), 120), HealthReading(at(2500), 0), HealthReading(at(2600), 301)))
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(record), null)) }
        val data = success(run { HealthImportReader(source).samples(window(), null) })
        assertTrue(data.samples.isEmpty())
        assertEquals(4, data.diagnostics.samsung.rejectedSamples)
        assertEquals(HealthDiagnosticReason.SAMSUNG_INTERVAL_WITHOUT_SAMPLES, data.diagnostics.reason())
    }

    @Test fun `instant intersection retains submillisecond session boundaries`() {
        val selected = HealthSession("session", SAMSUNG_HEALTH_ORIGIN, HealthWindow(at(2000).plusNanos(500000), at(3000).plusNanos(500000)))
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(
            HealthSeries("record", SAMSUNG_HEALTH_ORIGIN, at(1000), at(4000), listOf(
                HealthReading(at(2000), 120), HealthReading(at(2000).plusNanos(600000), 121),
                HealthReading(at(3000), 122), HealthReading(at(3000).plusNanos(600000), 123)))), null)) }
        assertEquals(listOf(121L, 122L), success(run { HealthImportReader(source).samples(window(), selected) }).samples.map { it.bpm })
    }

    @Test fun `equivalent Korea and UTC times have the same window`() {
        val korea = OffsetDateTime.parse("2025-01-02T10:00:00+09:00").toInstant()
        val utc = Instant.parse("2025-01-02T01:00:00Z")
        assertEquals(korea, utc)
        assertEquals(HealthWindow(korea, korea.plusSeconds(60)), HealthWindow(utc, utc.plusSeconds(60)))
    }

    @Test fun `touching or disjoint sessions fail before reading heart data`() {
        for (selected in listOf(session(start = 0, end = 1000), session(start = 5000, end = 6000), session(start = 6000, end = 7000))) {
            val source = Source()
            assertEquals(HealthResult.Failure(HealthFailure.NO_OVERLAP, HealthStage.TIME_RANGE), run { HealthImportReader(source).samples(window(), selected) })
            assertTrue(source.queries.isEmpty())
        }
    }

    @Test fun `foreign session cannot select Samsung samples`() {
        val source = Source()
        assertEquals(HealthResult.Failure(HealthFailure.WRONG_SOURCE, HealthStage.TIME_RANGE), run { HealthImportReader(source).samples(window(), session(origin = "example.other")) })
        assertTrue(source.queries.isEmpty())
    }

    @Test fun `no exercise candidates still permits explicit run time reading`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(2000))), null)) }
        val reader = HealthImportReader(source)
        assertTrue(success(run { reader.candidates(window()) }).sessions.isEmpty())
        assertEquals(1, success(run { reader.samples(window(), null) }).samples.size)
        assertEquals(window(), source.queries.single().first)
    }

    @Test fun `all candidates across pages are selectable and duplicates foreign and nonoverlap are excluded`() {
        val sessions = (1..25).map { session(id = "session-$it") }
        val source = Source().apply { sessionPages = mapOf(
            null to HealthPage(sessions.take(20) + session(origin = "example.other"), "next"),
            "next" to HealthPage(sessions.drop(20) + sessions.first() + session(id = "outside", start = 6000, end = 7000), "")) }
        val data = success(run { HealthImportReader(source).candidates(window()) })
        assertEquals(25, data.sessions.size)
        assertEquals(25, data.sessions.map { it.id }.toSet().size)
        assertEquals(2, data.pages)
        assertEquals(listOf(null, "next"), source.sessionTokens)
    }

    @Test fun `empty completion token stops after one heart page`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(2000))), "")) }
        assertEquals(1, success(run { HealthImportReader(source).samples(window(), null) }).samples.size)
        assertEquals(1, source.queries.size)
    }

    @Test fun `repeated token fails rather than returning a partial successful import`() {
        val source = Source().apply { heartPages = mapOf(
            null to HealthPage(listOf(series(samples = longArrayOf(2000))), "same"),
            "same" to HealthPage(listOf(series(id = "next", samples = longArrayOf(3000))), "same")) }
        assertEquals(HealthResult.Failure(HealthFailure.PAGE_CYCLE, HealthStage.HEART_READ), run { HealthImportReader(source).samples(window(), null) })
        assertEquals(2, source.queries.size)
    }

    @Test fun `candidate token cycles fail in the candidate stage`() {
        val source = Source().apply { sessionPages = mapOf(null to HealthPage(listOf(session()), "a"), "a" to HealthPage(listOf(session()), "a")) }
        assertEquals(HealthResult.Failure(HealthFailure.PAGE_CYCLE, HealthStage.SESSION_READ), run { HealthImportReader(source).candidates(window()) })
    }

    @Test fun `pages and repeated imports retain identical database keys with deterministic ordering`() {
        val record = series(samples = longArrayOf(3000, 2000, 2000))
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(record), "next"), "next" to HealthPage(listOf(record, series(id = "b", samples = longArrayOf(2500))), null)) }
        val reader = HealthImportReader(source)
        val first = success(run { reader.samples(window(), null) })
        val second = success(run { reader.samples(window(), null) })
        assertEquals(listOf(2000L, 2500L, 3000L), first.samples.map { it.epochMs })
        assertEquals(first.samples, second.samples)
        val persisted = linkedMapOf<Pair<String, Long>, ImportedHeart>()
        (first.samples + second.samples).forEach { persisted[it.sourceId to it.epochMs] = it }
        assertEquals(3, persisted.size)
        assertEquals(1, first.diagnostics.duplicateSamples)
        assertEquals(1, first.diagnostics.duplicateRecords)
    }

    @Test fun `unexpected foreign heart record never becomes an imported Samsung sample`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(origin = "example.other", samples = longArrayOf(2000))), null)) }
        val data = success(run { HealthImportReader(source).samples(window(), null) })
        assertTrue(data.samples.isEmpty())
        assertEquals(0, data.diagnostics.samsung.records)
    }

    @Test fun `source comparison distinguishes other source samples without returning them for storage`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(origin = "example.other", samples = longArrayOf(2500))), null)) }
        val data = success(run { HealthImportReader(source).diagnose(window(), session()) })
        assertEquals(HealthDiagnosticReason.OTHER_SOURCE_ONLY, data.reason())
        assertEquals(1, data.other.samplesInSelection)
        assertFalse(source.queries.single().second)
        assertEquals(window(), source.queries.single().first)
    }

    @Test fun `comparison distinguishes Samsung samples outside selected session from absent records`() {
        val source = Source().apply { heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(1000, 4500))), null)) }
        val data = success(run { HealthImportReader(source).diagnose(window(), session()) })
        assertEquals(HealthDiagnosticReason.SAMSUNG_OUTSIDE_SELECTION, data.reason())
        assertEquals(2, data.samsung.samplesInRun)
        assertEquals(0, data.samsung.samplesInSelection)
    }

    @Test fun `empty successful lookup makes no claim about Samsung Health internal storage`() {
        val data = success(run { HealthImportReader(Source()).diagnose(window(), null) })
        assertEquals(HealthDiagnosticReason.NO_SAMSUNG_RECORDS, data.reason())
        assertEquals(0, data.samsung.records)
    }

    @Test fun `provider unavailable and update required do not attempt reads`() {
        for ((status, reason) in listOf(HealthAvailability.UNSUPPORTED to HealthFailure.UNSUPPORTED, HealthAvailability.UPDATE_REQUIRED to HealthFailure.UPDATE_REQUIRED)) {
            val source = Source().apply { this.status = status }
            assertEquals(HealthResult.Failure(reason, HealthStage.AVAILABILITY), run { HealthImportReader(source).samples(window(), null) })
            assertTrue(source.queries.isEmpty())
        }
    }

    @Test fun `each operation checks both read permissions before requesting records`() {
        for (permission in listOf(HealthReadAccess(false, true), HealthReadAccess(true, false), HealthReadAccess(false, false))) {
            val source = Source().apply { this.permission = permission }
            assertEquals(HealthResult.Failure(HealthFailure.PERMISSION_REQUIRED, HealthStage.PERMISSION), run { HealthImportReader(source).samples(window(), null) })
            assertTrue(source.queries.isEmpty())
        }
        val source = Source()
        val reader = HealthImportReader(source)
        success(run { reader.candidates(window()) })
        source.permission = HealthReadAccess(false, true)
        assertEquals(HealthResult.Failure(HealthFailure.PERMISSION_REQUIRED, HealthStage.PERMISSION), run { reader.samples(window(), null) })
    }

    @Test fun `expired or failed later page does not produce partial success`() {
        val source = Source().apply {
            heartPages = mapOf(null to HealthPage(listOf(series(samples = longArrayOf(2000))), "expired"))
            failToken = "expired"
        }
        assertEquals(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.HEART_READ), run { HealthImportReader(source).samples(window(), null) })
        assertEquals(2, source.queries.size)
    }

    @Test fun `access rejection and timeout remain failures distinct from empty results`() {
        for (reason in listOf(HealthFailure.ACCESS_RESTRICTED, HealthFailure.TIMEOUT)) {
            val source = Source().apply { failToken = null; failReason = reason }
            assertEquals(HealthResult.Failure(reason, HealthStage.SOURCE_COMPARE), run { HealthImportReader(source).diagnose(window(), null) })
        }
    }

    @Test fun `cancelled reading propagates and does not become an empty or failed lookup`() {
        val source = Source().apply { cancellation = true }
        assertThrows(CancellationException::class.java) { run { HealthImportReader(source).samples(window(), null) } }
    }
}
