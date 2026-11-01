package com.runkeep.app.diagnostics

import java.io.OutputStream

object DiagnosticDocumentWriter {
    fun write(content: String, open: () -> OutputStream?) {
        val output = open() ?: error("unavailable")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }
}
