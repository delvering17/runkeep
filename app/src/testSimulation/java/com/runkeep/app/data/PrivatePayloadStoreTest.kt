package com.runkeep.app.data

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivatePayloadStoreTest {
    private fun withStore(test: (PrivatePayloadStore, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("course-payloads").toFile()
        try { test(PrivatePayloadStore(directory), directory) } finally { directory.deleteRecursively() }
    }

    @Test fun `small snapshots and legacy raw payloads need no file`() = withStore { store, directory ->
        val raw = "{\"name\":\"한글 코스\"}"
        assertEquals(raw, store.read(store.save(raw)))
        assertEquals(raw, store.read(raw))
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun `large snapshots use a short reference and survive recreating the store`() = withStore { store, directory ->
        val raw = "한글·좌표·안정된-ID→목표".repeat(100_000)
        val reference = store.save(raw)
        assertTrue(reference.length < 100)
        assertEquals(raw, PrivatePayloadStore(directory).read(reference))
        assertEquals(raw, store.read(reference))
    }

    @Test fun `consuming a service request removes only its snapshot`() = withStore { store, directory ->
        val first = store.save("a".repeat(20_000)); val second = store.save("b".repeat(20_000))
        assertEquals("a".repeat(20_000), store.read(first, consume = true))
        assertNull(store.read(first))
        assertEquals("b".repeat(20_000), store.read(second))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun `missing or path traversal references cannot restore a draft`() = withStore { store, _ ->
        assertNull(store.read("file:draft-missing.json"))
        assertNull(store.read("file:../draft-other.json"))
        assertNull(store.read("file:/tmp/draft-other.json"))
    }
}
