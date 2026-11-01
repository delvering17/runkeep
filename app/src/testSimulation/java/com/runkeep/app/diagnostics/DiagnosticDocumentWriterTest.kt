package com.runkeep.app.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticDocumentWriterTest {
    @Test fun `diagnostic document uses UTF8 and closes its stream`() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        DiagnosticDocumentWriter.write("{\"format\":\"합성 진단\"}") { output }
        assertEquals("{\"format\":\"합성 진단\"}", output.toString(Charsets.UTF_8))
        assertTrue(closed)
    }
    @Test fun `missing output and write failure stay failures`() {
        assertThrows(IllegalStateException::class.java) { DiagnosticDocumentWriter.write("{}") { null } }
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("synthetic failure") } }
        assertThrows(IOException::class.java) { DiagnosticDocumentWriter.write("{}") { broken } }
    }
}
