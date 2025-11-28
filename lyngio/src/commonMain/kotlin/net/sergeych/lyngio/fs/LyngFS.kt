/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyngio.fs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.*

/**
 * Async-first FS API (intermediate public surface).
 * Note: this is an interim step on the way to final exported entities; names/types may evolve,
 * but these APIs will stay public to ease migration.
 */
typealias LyngPath = Path

/** Metadata snapshot for a filesystem node. */
data class LyngMetadata(
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val size: Long?,
    val createdAtMillis: Long?,
    val modifiedAtMillis: Long?,
    val isSymlink: Boolean,
)

/**
 * Suspend-first, uniform filesystem. Heavy ops must not block the main thread or event loop.
 */
interface LyngFs {
    suspend fun exists(path: LyngPath): Boolean
    suspend fun list(dir: LyngPath): List<LyngPath>
    suspend fun readBytes(path: LyngPath): ByteArray
    suspend fun writeBytes(path: LyngPath, data: ByteArray, append: Boolean = false)
    suspend fun readUtf8(path: LyngPath): String
    suspend fun writeUtf8(path: LyngPath, text: String, append: Boolean = false)
    suspend fun metadata(path: LyngPath): LyngMetadata
    suspend fun createDirectories(dir: LyngPath, mustCreate: Boolean = false)
    suspend fun move(from: LyngPath, to: LyngPath, overwrite: Boolean = false)
    suspend fun delete(path: LyngPath, mustExist: Boolean = false, recursively: Boolean = false)

    /** Default implementation: naive read-all + write; backends may override for efficiency. */
    suspend fun copy(from: LyngPath, to: LyngPath, overwrite: Boolean = false) {
        if (!overwrite && exists(to)) error("Target exists: $to")
        val data = readBytes(from)
        writeBytes(to, data, append = false)
    }

    /** Glob search starting at [dir], matching [pattern] with `**`, `*`, and `?`. */
    suspend fun glob(dir: LyngPath, pattern: String): List<LyngPath> {
        val regex = globToRegex(pattern)
        val base = dir
        val out = mutableListOf<LyngPath>()
        suspend fun walk(d: LyngPath, rel: String) {
            for (child in list(d)) {
                val relPath = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (regex.matches(relPath)) out += child
                if (metadata(child).isDirectory) walk(child, relPath)
            }
        }
        walk(base, "")
        return out
    }
}

/** Okio-backed async implementation used on JVM/Android/Native and in-memory browser. */
class OkioAsyncFs(private val fs: FileSystem) : LyngFs {
    override suspend fun exists(path: LyngPath): Boolean = withContext(LyngIoDispatcher) {
        try { fs.metadataOrNull(path) != null } catch (_: Throwable) { false }
    }

    override suspend fun list(dir: LyngPath): List<LyngPath> = withContext(LyngIoDispatcher) {
        fs.list(dir)
    }

    override suspend fun readBytes(path: LyngPath): ByteArray = withContext(LyngIoDispatcher) {
        val buffer = Buffer()
        fs.source(path).buffer().use { it.readAll(buffer) }
        buffer.readByteArray()
    }

    override suspend fun writeBytes(path: LyngPath, data: ByteArray, append: Boolean) {
        withContext(LyngIoDispatcher) {
            val sink = if (append) fs.appendingSink(path) else fs.sink(path)
            sink.buffer().use { it.write(data) }
        }
    }

    override suspend fun readUtf8(path: LyngPath): String = withContext(LyngIoDispatcher) {
        val buffer = Buffer()
        fs.source(path).buffer().use { it.readAll(buffer) }
        buffer.readUtf8()
    }

    override suspend fun writeUtf8(path: LyngPath, text: String, append: Boolean) {
        withContext(LyngIoDispatcher) {
            val sink = if (append) fs.appendingSink(path) else fs.sink(path)
            sink.buffer().use { it.writeUtf8(text) }
        }
    }

    override suspend fun metadata(path: LyngPath): LyngMetadata = withContext(LyngIoDispatcher) {
        val m = fs.metadata(path)
        LyngMetadata(
            isRegularFile = m.isRegularFile,
            isDirectory = m.isDirectory,
            size = m.size,
            createdAtMillis = m.createdAtMillis,
            modifiedAtMillis = m.lastModifiedAtMillis,
            isSymlink = m.symlinkTarget != null,
        )
    }

    override suspend fun createDirectories(dir: LyngPath, mustCreate: Boolean) = withContext(LyngIoDispatcher) {
        fs.createDirectories(dir, mustCreate)
    }

    override suspend fun move(from: LyngPath, to: LyngPath, overwrite: Boolean) = withContext(LyngIoDispatcher) {
        if (overwrite) fs.delete(to, mustExist = false)
        fs.atomicMove(from, to)
    }

    override suspend fun delete(path: LyngPath, mustExist: Boolean, recursively: Boolean) = withContext(LyngIoDispatcher) {
        if (!recursively) {
            fs.delete(path, mustExist)
            return@withContext
        }
        fun deleteRec(p: Path) {
            val meta = fs.metadataOrNull(p)
            if (meta == null) {
                if (mustExist) throw IllegalStateException("No such file or directory: $p")
                return
            }
            if (meta.isDirectory) for (child in fs.list(p)) deleteRec(child)
            fs.delete(p, mustExist = false)
        }
        deleteRec(path)
        if (mustExist && fs.metadataOrNull(path) != null) error("Failed to delete: $path")
    }
}

/**
 * Default system FS selector per platform.
 * - JVM/Android/Native: Okio `FileSystem.SYSTEM`
 * - Node: native fs/promises implementation (non-blocking)
 * - Browser/Wasm: in-memory Okio `FakeFileSystem`
 */
expect fun platformAsyncFs(): LyngFs
expect val LyngIoDispatcher: CoroutineDispatcher

object LyngFS { // factory holder (interim name)
    fun system(): LyngFs = platformAsyncFs()
}

// --- helpers ---

private fun globToRegex(pattern: String): Regex {
    // Convert glob with **, *, ? into a Regex that matches relative POSIX paths
    val sb = StringBuilder()
    var i = 0
    sb.append('^')
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '*' -> {
                val isDouble = i + 1 < pattern.length && pattern[i + 1] == '*'
                if (isDouble) {
                    // ** → match across directories
                    // Consume optional following slash
                    val nextIsSlash = i + 2 < pattern.length && (pattern[i + 2] == '/' || pattern[i + 2] == '\\')
                    sb.append(".*")
                    if (nextIsSlash) i += 1 // skip one extra to consume slash later via i+=2 below
                    i += 1
                } else {
                    // * within a segment (no slash)
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            '/' , '\\' -> sb.append('/')
            else -> sb.append(c)
        }
        i += 1
    }
    sb.append('$')
    return Regex(sb.toString())
}
