package com.runkeep.app.diagnostics

import com.runkeep.core.*
import kotlinx.serialization.encodeToString
import java.io.File

class DiagnosticJournalStore(private val root: File, private val now: () -> Long = System::currentTimeMillis,
    private val runLimit: Long = 4 * 1024 * 1024, private val totalLimit: Long = 32 * 1024 * 1024,
    private val retentionMs: Long = 14 * 24 * 60 * 60_000L) {
    init { require(runLimit > 0 && totalLimit >= runLimit && retentionMs > 0) }
    private fun file(id: String, extension: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(root, "$id.$extension")
    }
    fun maintain(liveIds: Set<String>? = null, protected: String? = null) {
        if (!root.exists()) return
        val journals = root.listFiles()?.filter { it.extension == "jsonl" }.orEmpty()
        journals.filter { it.nameWithoutExtension != protected &&
            (now() - it.lastModified() > retentionMs || liveIds != null && it.nameWithoutExtension !in liveIds) }
            .forEach { delete(it.nameWithoutExtension) }
        root.listFiles()?.filter { it.extension == "loss" && !file(it.nameWithoutExtension, "jsonl").exists() }?.forEach { it.delete() }
    }
    fun append(id: String, event: DiagnosticEvent): Boolean {
        check(root.isDirectory || root.mkdirs())
        maintain(protected = id)
        val target = file(id, "jsonl")
        val bytes = (diagnosticCodec.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
        if (target.length() + bytes.size > runLimit) return false
        var total = root.listFiles()?.sumOf { it.length() } ?: 0
        for (old in root.listFiles().orEmpty().filter { it.extension == "jsonl" && it != target }.sortedBy { it.lastModified() }) {
            if (total + bytes.size + 1024 <= totalLimit) break
            val size = old.length() + file(old.nameWithoutExtension, "loss").length()
            delete(old.nameWithoutExtension)
            total -= size
        }
        if (total + bytes.size + 1024 > totalLimit) return false
        target.appendBytes(bytes)
        check(target.setLastModified(now()))
        return true
    }
    fun loss(id: String): DiagnosticLoss = file(id, "loss").takeIf { it.exists() }?.let {
        runCatching { diagnosticCodec.decodeFromString<DiagnosticLoss>(it.readText()) }.getOrElse { DiagnosticLoss(truncated = true) }
    } ?: DiagnosticLoss()
    fun saveLoss(id: String, value: DiagnosticLoss) {
        check(root.isDirectory || root.mkdirs())
        file(id, "loss").writeText(diagnosticCodec.encodeToString(value))
    }
    fun exists(id: String): Boolean = file(id, "jsonl").exists()
    fun read(id: String, activeSession: String? = null): DiagnosticFile? {
        maintain(protected = null)
        val target = file(id, "jsonl")
        if (!target.exists()) return null
        val events = mutableListOf<DiagnosticEvent>()
        var damaged = false
        target.bufferedReader().useLines { lines -> lines.forEach { line ->
            runCatching { diagnosticCodec.decodeFromString<DiagnosticEvent>(line) }
                .onSuccess { events += it }.onFailure { damaged = true }
        } }
        val unclosed = events.filter { it.session != activeSession }.groupBy { it.session }.values.any { session ->
            session.any { it.kind == DiagnosticKind.COLLECTION_START } && session.none { it.kind == DiagnosticKind.COLLECTION_STOP }
        }
        val loss = loss(id)
        return DiagnosticFile(loss = loss.copy(truncated = loss.truncated || damaged, uncleanSession = loss.uncleanSession || unclosed), events = events)
    }
    fun delete(id: String) {
        listOf(file(id, "jsonl"), file(id, "loss")).forEach { check(!it.exists() || it.delete()) }
    }
}
