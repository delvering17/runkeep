package com.runkeep.core

data class FileExportRequest(val fileName: String, val content: String)

data class FileExportState(val request: FileExportRequest? = null, val writing: Boolean = false) {
    val busy get() = request != null

    fun start(next: FileExportRequest): FileExportState = if (busy) this else FileExportState(next)

    fun beginWriting(): FileExportState = if (request == null || writing) this else copy(writing = true)

    fun finish(): FileExportState = FileExportState()
}
