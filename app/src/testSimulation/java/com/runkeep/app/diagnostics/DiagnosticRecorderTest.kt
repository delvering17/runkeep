package com.runkeep.app.diagnostics

import com.runkeep.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticRecorderTest {
    private val environment = DiagnosticEnvironment("synthetic", 1, "simulation", "preview", 36,
        DiagnosticSettings(false, 20, false, false, false))
    @Test fun `enable disable sessions leave uncollected intervals and restart explicit`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-capture").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            var clock = 0L
            val recorder = DiagnosticRecorder(root, scope, { clock }, { 1_000 + clock }, { _, _ -> environment })
            val run = RunSummary(id = "synthetic-run", startedAt = 1_000)
            val off = Preferences(autoPause = false)
            val on = off.copy(diagnosticRecording = true)
            recorder.configure(run, off, off)
            recorder.tick(run, 0)
            assertNull(recorder.snapshot(run.id))
            clock = 5_000
            recorder.configure(run, on, off)
            recorder.tick(run, 0)
            clock = 7_000
            recorder.configure(run, off, off)
            recorder.tick(run, 0)
            clock = 12_000
            recorder.configure(run, on, off, recovery = true)
            recorder.stop(DiagnosticReason.FINISHED, run.copy(status = RunStatus.FINISHED))
            val data = recorder.snapshot(run.id)!!
            val starts = data.events.filter { it.kind == DiagnosticKind.COLLECTION_START }
            assertEquals(listOf(5_000L, 12_000L), starts.map { it.runOffsetMs })
            assertEquals(2, starts.map { it.session }.distinct().size)
            assertTrue(data.events.any { it.reason == DiagnosticReason.SERVICE_RESTART })
            assertTrue(data.events.none { it.runOffsetMs in 7_001..11_999 })
            assertFalse(data.loss.incomplete)
            assertTrue(data.events.any { it.reason == DiagnosticReason.MAP_NOT_OPENED })
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
    @Test fun `bounded queue exposes loss without blocking caller and deletion prevents late recreation`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-queue").toFile()
        val job = SupervisorJob()
        try {
            val recorder = DiagnosticRecorder(root, CoroutineScope(job + Dispatchers.Default), { 0 }, { 0 }, { _, _ -> environment }, queueCapacity = 1)
            val run = RunSummary(id = "synthetic-run", startedAt = 0)
            recorder.configure(run, Preferences(diagnosticRecording = true), Preferences())
            repeat(10_000) { recorder.tick(run, 0) }
            val data = withTimeout(10_000) { recorder.snapshot(run.id) }!!
            assertTrue(data.loss.queueDropped > 0)
            assertTrue(recorder.status.value.contains("유실"))
            recorder.delete(run.id)
            recorder.tick(run, 0)
            assertNull(recorder.snapshot(run.id))
            assertFalse(root.resolve("${run.id}.jsonl").exists())
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
    @Test fun `storage failure is explicit and does not throw into workout`() = runBlocking {
        val root = Files.createTempFile("synthetic-unwritable", ".tmp").toFile()
        val job = SupervisorJob()
        try {
            val recorder = DiagnosticRecorder(root, CoroutineScope(job + Dispatchers.Default), { 0 }, { 0 }, { _, _ -> environment })
            val run = RunSummary(id = "synthetic-run", startedAt = 0)
            recorder.configure(run, Preferences(diagnosticRecording = true), Preferences())
            recorder.tick(run, 0)
            assertTrue(withTimeout(10_000) { recorder.snapshot(run.id) }!!.loss.storageFailed)
            assertTrue(recorder.status.value.contains("실패"))
        } finally { job.cancelAndJoin(); root.delete() }
    }
    @Test fun `history does not create missing past collection or invent successful map updates`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-map").toFile()
        val job = SupervisorJob()
        try {
            val recorder = DiagnosticRecorder(root, CoroutineScope(job + Dispatchers.Default), { 0 }, { 0 }, { _, _ -> environment })
            recorder.historyMap("legacy", 0, MapDiagnostic(DiagnosticReason.SDK_NOT_READY, 1, 3, 0))
            assertNull(recorder.snapshot("legacy"))
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
