package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Atomically returns the first variant stored for this user/experiment; never evict live assignments. */
fun interface StickyAssignmentStore {
    fun getOrPut(userId: String, experimentKey: String, proposedVariant: String): String
}

class InMemoryStickyAssignmentStore @JvmOverloads constructor(private val capacity: Int = 100_000) : StickyAssignmentStore {
    private val assignments = HashMap<Pair<String, String>, String>()
    init { require(capacity > 0) }
    @Synchronized override fun getOrPut(userId: String, experimentKey: String, proposedVariant: String): String {
        val key = userId to experimentKey
        return assignments[key] ?: run {
            check(assignments.size < capacity) { "Sticky assignment capacity reached" }
            assignments[key] = proposedVariant
            proposedVariant
        }
    }
}

/** Local durable store. A shared directory and OS lock also coordinate processes on the same host. */
class FileStickyAssignmentStore(directory: Path) : StickyAssignmentStore {
    private val directory = directory.toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()
    private val mapper = jacksonObjectMapper()
    private data class Entry(val userId: String, val experimentKey: String, val variant: String)
    override fun getOrPut(userId: String, experimentKey: String, proposedVariant: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest("${experimentKey.length}:$experimentKey:$userId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        synchronized(monitors.computeIfAbsent(directory) { Any() }) {
            FileChannel.open(directory.resolve(".lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                val file = directory.resolve("$key.json")
                if (Files.exists(file)) {
                    val entry = mapper.readValue<Entry>(Files.readString(file))
                    check(entry.userId == userId && entry.experimentKey == experimentKey) { "Sticky assignment identity mismatch" }
                    return entry.variant
                }
                val temporary = Files.createTempFile(directory, ".assignment-", ".tmp")
                try {
                    FileChannel.open(temporary, WRITE).use { data ->
                        val bytes = java.nio.ByteBuffer.wrap(mapper.writeValueAsBytes(Entry(userId, experimentKey, proposedVariant)))
                        while (bytes.hasRemaining()) data.write(bytes)
                        data.force(true)
                    }
                    Files.move(temporary, file, ATOMIC_MOVE)
                    FileChannel.open(directory, READ).use { it.force(true) }
                } finally { Files.deleteIfExists(temporary) }
                return proposedVariant
            } }
        }
    }
    companion object { private val monitors = ConcurrentHashMap<Path, Any>() }
}
