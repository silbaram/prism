package io.github.silbaram.prism.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.*

class StickyAssignmentStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun `durable store survives reopening and concurrent instances return one first assignment`() {
        val first = FileStickyAssignmentStore(directory)
        assertEquals("A", first.getOrPut("u", "experiment", "A"))
        assertEquals("A", FileStickyAssignmentStore(directory).getOrPut("u", "experiment", "B"))
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { i -> workers.submit<String> {
                FileStickyAssignmentStore(directory).getOrPut("concurrent", "experiment", "variant-$i")
            } }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.toSet().size)
            assertEquals(results.first(), FileStickyAssignmentStore(directory).getOrPut("concurrent", "experiment", "changed"))
        } finally { workers.shutdownNow() }
        assertEquals("other", first.getOrPut("u", "different", "other"))
    }
    @Test fun `file IO errors propagate instead of returning an unsaved assignment`() {
        val store = FileStickyAssignmentStore(directory)
        java.nio.file.Files.createDirectory(directory.resolve(".lock"))
        assertThrows(java.io.IOException::class.java) { store.getOrPut("u", "experiment", "A") }
        java.nio.file.Files.delete(directory.resolve(".lock"))
        assertEquals("B", store.getOrPut("u", "experiment", "B"))
        assertEquals("B", FileStickyAssignmentStore(directory).getOrPut("u", "experiment", "A"))
    }

    @Test fun `capacity exhaustion rejects new identities without evicting prior assignments`() {
        val store = InMemoryStickyAssignmentStore(1)
        assertEquals("A", store.getOrPut("u", "experiment", "A"))
        assertThrows(IllegalStateException::class.java) { store.getOrPut("other", "experiment", "B") }
        assertEquals("A", store.getOrPut("u", "experiment", "B"))
    }
}
