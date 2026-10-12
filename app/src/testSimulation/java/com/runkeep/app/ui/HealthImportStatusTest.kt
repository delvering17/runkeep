package com.runkeep.app.ui

import com.runkeep.core.healthimport.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthImportStatusTest {
    @Test fun `unavailable permission and provider read failure have separate safe messages`() {
        val messages = listOf(HealthFailure.UNSUPPORTED, HealthFailure.UPDATE_REQUIRED, HealthFailure.PERMISSION_REQUIRED,
            HealthFailure.ACCESS_RESTRICTED, HealthFailure.TIMEOUT, HealthFailure.PAGE_CYCLE).map {
            historyHealthFailure(HealthResult.Failure(it, HealthStage.HEART_READ))
        }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
        assertTrue(messages[3].contains("제한"))
        assertTrue(messages[4].contains("저장하지"))
    }

    @Test fun `read stage failure is distinct from an empty successful lookup`() {
        val failure = historyHealthFailure(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.SOURCE_COMPARE))
        val empty = historyHealthEmpty(HealthDiagnostics(1, HealthSourceCounts()))
        assertTrue(failure.contains("완료하지 못"))
        assertTrue(empty.contains("이번 Health Connect 조회"))
        assertFalse(empty.contains("전송되지 않았"))
    }

    @Test fun `other sources and Samsung outside selection lead to different recovery actions`() {
        val other = historyHealthEmpty(HealthDiagnostics(1, HealthSourceCounts(), HealthSourceCounts(records = 1, samplesInSelection = 2)))
        val outside = historyHealthEmpty(HealthDiagnostics(1, HealthSourceCounts(records = 1, samplesInRun = 2)))
        val retry = historyHealthEmpty(HealthDiagnostics(1, HealthSourceCounts(records = 1, samplesInSelection = 2)))
        assertTrue(other.contains("다른 출처"))
        assertTrue(outside.contains("다시 선택"))
        assertTrue(retry.contains("다시 가져오"))
        assertNotEquals(other, outside)
    }
}

class HistoryHealthGateTest {
    @Test fun `permission query read and save share one request lock`() {
        val gate = HistoryHealthGate()
        val permission = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.accepts(permission))
        // The permission callback continues with the same token; query/read/save cannot overlap it.
        assertNull(gate.begin())
        gate.finish(permission)
        val import = gate.begin()!!
        assertFalse(gate.accepts(permission))
        assertTrue(gate.accepts(import))
        assertNull(gate.begin())
        gate.finish(import)
        assertNotNull(gate.begin())
    }
    @Test fun `cancel suppresses late results and stale completion cannot unlock a new request`() {
        val gate = HistoryHealthGate()
        val old = gate.begin()!!
        gate.cancel()
        val current = gate.begin()!!
        assertFalse(gate.accepts(old))
        gate.finish(old)
        assertNull(gate.begin())
        assertTrue(gate.accepts(current))
    }
    @Test fun `record switch and revisiting the same ID reject old permission read save callbacks`() {
        val original = HistoryHealthGate()
        val pending = original.begin()!!
        original.close()
        val revisited = HistoryHealthGate()
        val current = revisited.begin()!!
        assertFalse(original.accepts(pending))
        assertFalse(revisited.accepts(pending))
        assertTrue(revisited.accepts(current))
        assertNull(original.begin())
    }
    @Test fun `cancelled read cannot begin save and a completed request may be retried`() {
        val gate = HistoryHealthGate()
        val read = gate.begin()!!
        gate.cancel()
        var writes = 0
        if (gate.accepts(read)) writes++
        assertEquals(0, writes)
        val retry = gate.begin()!!
        if (gate.accepts(retry)) writes++
        gate.finish(retry)
        assertEquals(1, writes)
        assertFalse(gate.accepts(retry))
        assertNotNull(gate.begin())
    }
}
