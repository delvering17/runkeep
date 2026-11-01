package com.runkeep.app.diagnostics

import com.runkeep.core.*
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticJournalStoreTest {
    private fun withRoot(test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("synthetic-diagnostics").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun event(kind: DiagnosticKind, session: String = "synthetic") = DiagnosticEvent(kind = kind, session = session)
    @Test fun `old workouts have no data and interrupted sessions stay incomplete`() = withRoot { root ->
        val store = DiagnosticJournalStore(root)
        assertNull(store.read("legacy"))
        assertTrue(store.append("run", event(DiagnosticKind.COLLECTION_START)))
        assertTrue(store.read("run")!!.loss.uncleanSession)
        store.append("run", event(DiagnosticKind.COLLECTION_STOP))
        assertFalse(store.read("run")!!.loss.incomplete)
        store.append("run", event(DiagnosticKind.COLLECTION_START, "restart"))
        assertTrue(store.read("run")!!.loss.uncleanSession)
    }
    @Test fun `capacity refuses extra bytes and loss survives reopening`() = withRoot { root ->
        val store = DiagnosticJournalStore(root, runLimit = 1024, totalLimit = 4096)
        var accepted = 0
        while (store.append("run", event(DiagnosticKind.LOCATION_RECEIVED))) accepted++
        assertTrue(accepted > 0)
        assertTrue(root.resolve("run.jsonl").length() <= 1024)
        store.saveLoss("run", DiagnosticLoss(queueDropped = 7, storageFailed = true, capacityReached = true))
        assertEquals(7L, DiagnosticJournalStore(root).read("run")!!.loss.queueDropped)
        assertTrue(DiagnosticJournalStore(root).read("run")!!.loss.storageFailed)
    }
    @Test fun `retention orphan cleanup and explicit deletion remove associated diagnostics`() = withRoot { root ->
        var time = 1_000L
        val store = DiagnosticJournalStore(root, now = { time }, retentionMs = 100)
        store.append("old", event(DiagnosticKind.CHECKPOINT))
        store.saveLoss("old", DiagnosticLoss(queueDropped = 1))
        time += 101
        store.maintain()
        assertNull(store.read("old"))
        assertFalse(root.resolve("old.loss").exists())
        store.append("orphan", event(DiagnosticKind.CHECKPOINT))
        store.append("retained", event(DiagnosticKind.CHECKPOINT))
        store.maintain(setOf("retained"))
        assertNull(store.read("orphan"))
        assertNotNull(store.read("retained"))
        store.delete("retained")
        assertNull(store.read("retained"))
    }
    @Test fun `total limit evicts oldest other workout while protecting current workout`() = withRoot { root ->
        var time = 1_000L
        val store = DiagnosticJournalStore(root, now = { time }, runLimit = 1024, totalLimit = 2100)
        while (store.append("old", event(DiagnosticKind.CHECKPOINT))) Unit
        time++
        while (store.append("current", event(DiagnosticKind.CHECKPOINT))) Unit
        assertNull(store.read("old"))
        assertNotNull(store.read("current"))
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 2100)
    }
    @Test fun `damaged tail preserves valid events and reports truncation`() = withRoot { root ->
        val store = DiagnosticJournalStore(root)
        store.append("run", event(DiagnosticKind.CHECKPOINT))
        root.resolve("run.jsonl").appendText("{invalid")
        assertEquals(1, store.read("run")!!.events.size)
        assertTrue(store.read("run")!!.loss.truncated)
        assertThrows(IllegalArgumentException::class.java) { store.read("../escape") }
    }
    @Test fun `share files are limited expire and delete only matching workout`() = withRoot { root ->
        var time = System.currentTimeMillis()
        val shares = DiagnosticShareFiles(root) { time }
        val a = shares.create("one", "{}")
        time++
        shares.create("two", "{}")
        time++
        shares.create("three", "{}")
        assertFalse(a.exists())
        assertEquals(2, root.listFiles()!!.size)
        shares.deleteRun("two")
        assertEquals(1, root.listFiles()!!.size)
        time += 60 * 60_000L + 1
        shares.clean()
        assertEquals(0, root.listFiles()!!.size)
    }
}
