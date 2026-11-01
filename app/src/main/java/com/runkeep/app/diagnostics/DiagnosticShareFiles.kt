package com.runkeep.app.diagnostics

import java.io.File
import java.util.UUID

class DiagnosticShareFiles(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    fun clean() {
        root.listFiles()?.filter { now() - it.lastModified() >= 60 * 60_000L }?.forEach { check(it.delete()) }
    }
    fun create(runId: String, content: String): File {
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        check(root.isDirectory || root.mkdirs())
        clean()
        root.listFiles().orEmpty().sortedBy { it.lastModified() }.dropLast(1).forEach { check(it.delete()) }
        val file = File(root, "$runId-${UUID.randomUUID()}.json")
        try { file.writeText(content, Charsets.UTF_8); check(file.setLastModified(now())) } catch (failure: Exception) { file.delete(); throw failure }
        return file
    }
    fun deleteRun(runId: String) {
        require(runId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        root.listFiles()?.filter { it.name.startsWith("$runId-") }?.forEach { check(it.delete()) }
    }
}
