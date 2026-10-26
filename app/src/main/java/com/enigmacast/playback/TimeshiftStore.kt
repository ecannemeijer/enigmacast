package com.enigmacast.playback

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What [TimeshiftStore.write] did with the bytes it was offered. */
internal enum class WriteResult { ACCEPTED, FULL, CLOSED }

/**
 * Growing MPEG-TS buffer for live pause. Bytes keep a fixed virtual address, so throwing
 * away what the player has already read does not move the rest. The player reads through
 * [read]; a pump appends with [write]. [read] returns 0 at the live edge instead of an
 * end, and [await] waits until more bytes, a failure, or [close].
 *
 * Completed chunks sit in files. The chunk still being written stays in memory, so the
 * writer never holds a file open while the player reads it.
 */
internal class TimeshiftStore(
    val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val chunkBytes: Int = CHUNK_BYTES,
) {
    init {
        require(maxBytes > 0)
        require(chunkBytes > 0)
    }

    /** The folder is made by the writer on its first full chunk, so constructing never touches disk. */
    private var directoryReady = false

    private val lock = ReentrantLock()
    private val readable = lock.newCondition()
    private val tail = ByteArray(chunkBytes)
    private var tailStart = 0L
    private var tailLength = 0
    private var written = 0L
    private var retained = 0L
    private var closed = false
    private var error: IOException? = null

    /** Appends [length] bytes. The whole offer is refused once it would pass [maxBytes]. */
    fun write(data: ByteArray, offset: Int, length: Int): WriteResult {
        lock.withLock {
            if (closed || error != null) return WriteResult.CLOSED
            if (length <= 0) return WriteResult.ACCEPTED
            if (written - retained + length > maxBytes) return WriteResult.FULL
            var src = offset
            var left = length
            while (left > 0) {
                if (tailLength == chunkBytes) flushTail()
                val n = minOf(left, chunkBytes - tailLength)
                System.arraycopy(data, src, tail, tailLength, n)
                tailLength += n
                written += n
                src += n
                left -= n
                if (tailLength == chunkBytes) flushTail()
            }
            readable.signalAll()
            return WriteResult.ACCEPTED
        }
    }

    /**
     * Copies bytes at the virtual [position]. Returns how many were copied, 0 when [position]
     * is the live edge, or -1 when that address was already discarded.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            if (position < retained) return -1
            if (position >= written || length <= 0) return 0
            var pos = position
            var dst = offset
            var left = minOf(length.toLong(), written - position).toInt()
            var copied = 0
            while (left > 0) {
                val n = if (pos >= tailStart) {
                    val tailOff = (pos - tailStart).toInt()
                    val count = minOf(left, tailLength - tailOff)
                    if (count <= 0) break
                    System.arraycopy(tail, tailOff, buffer, dst, count)
                    count
                } else {
                    val chunkOff = (pos % chunkBytes).toInt()
                    val count = minOf(left, chunkBytes - chunkOff)
                    RandomAccessFile(chunkFile(pos / chunkBytes), "r").use { file ->
                        file.seek(chunkOff.toLong())
                        val got = file.read(buffer, dst, count)
                        if (got <= 0) return copied
                        got
                    }
                }
                pos += n
                dst += n
                left -= n
                copied += n
            }
            return copied
        }
    }

    /** Drops chunks that end at or before [position]. Bytes at [position] and after stay. */
    fun discardBefore(position: Long) {
        lock.withLock {
            val target = position.coerceIn(retained, written)
            while (retained + chunkBytes <= target && retained < tailStart) {
                chunkFile(retained / chunkBytes).delete()
                retained += chunkBytes
            }
            if (target >= written && retained >= tailStart) {
                tailLength = 0
                tailStart = written
                retained = written
            }
        }
    }

    /** Waits until [position] can be read, the store fails or closes, or [blocked] becomes false. */
    fun await(position: Long, timeoutMs: Long, blocked: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.withLock {
            while (blocked() && position >= written && error == null && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                try {
                    readable.await(left, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    fun wake() {
        lock.withLock { readable.signalAll() }
    }

    fun failure(): IOException? = lock.withLock { error }

    fun isClosed(): Boolean = lock.withLock { closed }

    fun fail(cause: IOException) {
        lock.withLock {
            if (error == null) error = cause
            closed = true
            readable.signalAll()
        }
    }

    /** Unblocks readers. Files stay until [delete]. */
    fun close() {
        lock.withLock {
            closed = true
            readable.signalAll()
        }
    }

    fun delete() {
        close()
        directory.deleteRecursively()
    }

    private fun flushTail() {
        if (!directoryReady) {
            directory.mkdirs()
            directoryReady = true
        }
        val file = chunkFile(tailStart / chunkBytes)
        file.writeBytes(tail.copyOf(tailLength))
        tailStart += tailLength
        tailLength = 0
    }

    private fun chunkFile(index: Long): File = File(directory, index.toString())

    companion object {
        const val MAX_BYTES = 1024L * 1024L * 1024L
        const val CHUNK_BYTES = 4 * 1024 * 1024
    }
}
