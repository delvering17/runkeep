package com.runkeep.app.data

import java.io.File

class PrivatePayloadStore(private val directory: File) {
    fun save(value: String): String {
        if (value.length <= 16_384) return "inline:$value"
        check(directory.isDirectory || directory.mkdirs()) { "초안 보관 공간을 만들 수 없습니다" }
        val file = File.createTempFile("draft-", ".json", directory)
        try { file.writeText(value, Charsets.UTF_8) } catch (error: Exception) { file.delete(); throw error }
        return "file:${file.name}"
    }

    fun read(reference: String, consume: Boolean = false): String? {
        if (reference.startsWith("inline:")) return reference.removePrefix("inline:")
        if (!reference.startsWith("file:")) return reference
        val name = reference.removePrefix("file:")
        if (!name.matches(Regex("draft-[A-Za-z0-9-]+\\.json"))) return null
        val file = File(directory, name)
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.also { if (consume) file.delete() }
    }
}
