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

/*
 * Lyng FS module installer and bindings
 */

package net.sergeych.lyng.io.fs

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyngio.fs.LyngFS
import net.sergeych.lyngio.fs.LyngFs
import net.sergeych.lyngio.fs.LyngPath
import net.sergeych.lyngio.fs.security.AccessDeniedException
import net.sergeych.lyngio.fs.security.FsAccessPolicy
import net.sergeych.lyngio.fs.security.LyngFsSecured
import okio.Path.Companion.toPath

/**
 * Install Lyng module `lyng.io.fs` into the given scope's ImportManager.
 * Returns true if installed, false if it was already registered in this manager.
 */
fun createFsModule(policy: FsAccessPolicy, scope: Scope): Boolean =
    createFsModule(policy, scope.importManager)

// Alias as requested earlier in discussions
fun createFs(policy: FsAccessPolicy, scope: Scope): Boolean = createFsModule(policy, scope)

/** Same as [createFsModule] but with explicit [ImportManager]. */
fun createFsModule(policy: FsAccessPolicy, manager: ImportManager): Boolean {
    val name = "lyng.io.fs"
    // Avoid re-registering in this ImportManager
    if (manager.packageNames.contains(name)) return false

    manager.addPackage(name) { module ->
        buildFsModule(module, policy)
    }
    return true
}

// Alias overload for ImportManager
fun createFs(policy: FsAccessPolicy, manager: ImportManager): Boolean = createFsModule(policy, manager)

// --- Module builder ---

private suspend fun buildFsModule(module: ModuleScope, policy: FsAccessPolicy) {
    // Per-module secured FS, captured by all factories and methods
    val base: LyngFs = LyngFS.system()
    val secured = LyngFsSecured(base, policy)

    // Path class bound to this module
    val pathType = object : ObjClass("Path") {
        override suspend fun callOn(scope: Scope): Obj {
            val arg = scope.requireOnlyArg<ObjString>()
            val str = arg.value
            return ObjPath(this, secured, str.toPath())
        }
    }.apply {
        addFnDoc(
            name = "name",
            doc = "Base name of the path (last segment).",
            returns = type("lyng.String"),
            moduleName = module.packageName
        ) {
            val self = thisAs<ObjPath>()
            self.path.name.toObj()
        }
        addFnDoc(
            name = "parent",
            doc = "Parent directory as a Path or null if none.",
            returns = type("Path", nullable = true),
            moduleName = module.packageName
        ) {
            val self = thisAs<ObjPath>()
            self.path.parent?.let {
                ObjPath( this@apply, self.secured, it)
            } ?: ObjNull
        }
        addFnDoc(
            name = "segments",
            doc = "List of path segments.",
            // returns: List<String>
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
            moduleName = module.packageName
        ) {
            val self = thisAs<ObjPath>()
            ObjList(self.path.segments.map { ObjString(it) }.toMutableList())
        }
        // exists(): Bool
        addFnDoc(
            name = "exists",
            doc = "Check whether this path exists.",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                (self.secured.exists(self.path)).toObj()
            }
        }
        // isFile(): Bool — cached metadata
        addFnDoc(
            name = "isFile",
            doc = "True if this path is a regular file (based on cached metadata).",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                self.ensureMetadata().let { ObjBool(it.isRegularFile) }
            }
        }
        // isDirectory(): Bool — cached metadata
        addFnDoc(
            name = "isDirectory",
            doc = "True if this path is a directory (based on cached metadata).",
            returns = type("lyng.Bool"),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                self.ensureMetadata().let { ObjBool(it.isDirectory) }
            }
        }
        // size(): Int? — null when unavailable
        addFnDoc(
            name = "size",
            doc = "File size in bytes, or null when unavailable.",
            returns = type("lyng.Int", nullable = true),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.ensureMetadata()
                m.size?.let { ObjInt(it) } ?: ObjNull
            }
        }
        // createdAt(): Instant? — Lyng Instant, null when unavailable
        addFnDoc(
            name = "createdAt",
            doc = "Creation time as `Instant`, or null when unavailable.",
            returns = type("lyng.Instant", nullable = true),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.ensureMetadata()
                m.createdAtMillis?.let { ObjInstant(kotlinx.datetime.Instant.fromEpochMilliseconds(it)) } ?: ObjNull
            }
        }
        // createdAtMillis(): Int? — milliseconds since epoch or null
        addFnDoc(
            name = "createdAtMillis",
            doc = "Creation time in milliseconds since epoch, or null when unavailable.",
            returns = type("lyng.Int", nullable = true),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.ensureMetadata()
                m.createdAtMillis?.let { ObjInt(it) } ?: ObjNull
            }
        }
        // modifiedAt(): Instant? — Lyng Instant, null when unavailable
        addFnDoc(
            name = "modifiedAt",
            doc = "Last modification time as `Instant`, or null when unavailable.",
            returns = type("lyng.Instant", nullable = true),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.ensureMetadata()
                m.modifiedAtMillis?.let { ObjInstant(kotlinx.datetime.Instant.fromEpochMilliseconds(it)) } ?: ObjNull
            }
        }
        // modifiedAtMillis(): Int? — milliseconds since epoch or null
        addFnDoc(
            name = "modifiedAtMillis",
            doc = "Last modification time in milliseconds since epoch, or null when unavailable.",
            returns = type("lyng.Int", nullable = true),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.ensureMetadata()
                m.modifiedAtMillis?.let { ObjInt(it) } ?: ObjNull
            }
        }
        // list(): List<Path>
        addFnDoc(
            name = "list",
            doc = "List directory entries as `Path` objects.",
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("Path"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val items = self.secured.list(self.path).map { ObjPath(self.objClass, self.secured, it) }
                ObjList(items.toMutableList())
            }
        }
        // readBytes(): Buffer
        addFnDoc(
            name = "readBytes",
            doc = "Read the file into a binary buffer.",
            returns = type("lyng.Buffer"),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val bytes = self.secured.readBytes(self.path)
                ObjBuffer(bytes.asUByteArray())
            }
        }
        // writeBytes(bytes: Buffer)
        addFnDoc(
            name = "writeBytes",
            doc = "Write a binary buffer to the file, replacing content.",
            params = listOf(ParamDoc("bytes", type("lyng.Buffer"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val buf = requiredArg<ObjBuffer>(0)
                self.secured.writeBytes(self.path, buf.byteArray.asByteArray(), append = false)
                ObjVoid
            }
        }
        // appendBytes(bytes: Buffer)
        addFnDoc(
            name = "appendBytes",
            doc = "Append a binary buffer to the end of the file.",
            params = listOf(ParamDoc("bytes", type("lyng.Buffer"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val buf = requiredArg<ObjBuffer>(0)
                self.secured.writeBytes(self.path, buf.byteArray.asByteArray(), append = true)
                ObjVoid
            }
        }
        // readUtf8(): String
        addFnDoc(
            name = "readUtf8",
            doc = "Read the file as a UTF-8 string.",
            returns = type("lyng.String"),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                self.secured.readUtf8(self.path).toObj()
            }
        }
        // writeUtf8(text: String)
        addFnDoc(
            name = "writeUtf8",
            doc = "Write a UTF-8 string to the file, replacing content.",
            params = listOf(ParamDoc("text", type("lyng.String"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val text = requireOnlyArg<ObjString>().value
                self.secured.writeUtf8(self.path, text, append = false)
                ObjVoid
            }
        }
        // appendUtf8(text: String)
        addFnDoc(
            name = "appendUtf8",
            doc = "Append UTF-8 text to the end of the file.",
            params = listOf(ParamDoc("text", type("lyng.String"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val text = requireOnlyArg<ObjString>().value
                self.secured.writeUtf8(self.path, text, append = true)
                ObjVoid
            }
        }
        // metadata(): Map
        addFnDoc(
            name = "metadata",
            doc = "Fetch cached metadata as a map of fields: `isFile`, `isDirectory`, `size`, `createdAtMillis`, `modifiedAtMillis`, `isSymlink`.",
            returns = TypeGenericDoc(type("lyng.Map"), listOf(type("lyng.String"), type("lyng.Any"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val m = self.secured.metadata(self.path)
                ObjMap(mutableMapOf(
                    ObjString("isFile") to ObjBool(m.isRegularFile),
                    ObjString("isDirectory") to ObjBool(m.isDirectory),
                    ObjString("size") to (m.size?.toLong() ?: 0L).toObj(),
                    ObjString("createdAtMillis") to ((m.createdAtMillis ?: 0L)).toObj(),
                    ObjString("modifiedAtMillis") to ((m.modifiedAtMillis ?: 0L)).toObj(),
                    ObjString("isSymlink") to ObjBool(m.isSymlink),
                ))
            }
        }
        // mkdirs(mustCreate: Bool=false)
        addFnDoc(
            name = "mkdirs",
            doc = "Create directories (like `mkdir -p`). Optional `mustCreate` enforces error if target exists.",
            params = listOf(ParamDoc("mustCreate", type("lyng.Bool"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val mustCreate = args.list.getOrNull(0)?.toBool() ?: false
                self.secured.createDirectories(self.path, mustCreate)
                ObjVoid
            }
        }
        // move(to: Path|String, overwrite: Bool=false)
        addFnDoc(
            name = "move",
            doc = "Move this path to a new location. `to` may be a `Path` or `String`. Use `overwrite` to replace existing target.",
            // types vary; keep generic description in doc
            params = listOf(ParamDoc("to"), ParamDoc("overwrite", type("lyng.Bool"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val toPath = parsePathArg(this, self, requiredArg<Obj>(0))
                val overwrite = args.list.getOrNull(1)?.toBool() ?: false
                self.secured.move(self.path, toPath, overwrite)
                ObjVoid
            }
        }
        // delete(mustExist: Bool=false, recursively: Bool=false)
        addFnDoc(
            name = "delete",
            doc = "Delete this path. Optional flags: `mustExist` and `recursively`.",
            params = listOf(ParamDoc("mustExist", type("lyng.Bool")), ParamDoc("recursively", type("lyng.Bool"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val mustExist = args.list.getOrNull(0)?.toBool() ?: false
                val recursively = args.list.getOrNull(1)?.toBool() ?: false
                self.secured.delete(self.path, mustExist, recursively)
                ObjVoid
            }
        }
        // copy(to: Path|String, overwrite: Bool=false)
        addFnDoc(
            name = "copy",
            doc = "Copy this path to a new location. `to` may be a `Path` or `String`. Use `overwrite` to replace existing target.",
            params = listOf(ParamDoc("to"), ParamDoc("overwrite", type("lyng.Bool"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val toPath = parsePathArg(this, self, requiredArg<Obj>(0))
                val overwrite = args.list.getOrNull(1)?.toBool() ?: false
                self.secured.copy(self.path, toPath, overwrite)
                ObjVoid
            }
        }
        // glob(pattern: String): List<Path>
        addFnDoc(
            name = "glob",
            doc = "List entries matching a glob pattern (no recursion).",
            params = listOf(ParamDoc("pattern", type("lyng.String"))),
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("Path"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val pattern = requireOnlyArg<ObjString>().value
                val matches = self.secured.glob(self.path, pattern)
                ObjList(matches.map { ObjPath(self.objClass, self.secured, it) }.toMutableList())
            }
        }

        // --- streaming readers (initial version: chunk from whole content, API stable) ---

        // readChunks(size: Int = 65536) -> Iterator<Buffer>
        addFnDoc(
            name = "readChunks",
            doc = "Read file in fixed-size chunks as an iterator of `Buffer`.",
            params = listOf(ParamDoc("size", type("lyng.Int"))),
            returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Buffer"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val size = args.list.getOrNull(0)?.toInt() ?: 65536
                val bytes = self.secured.readBytes(self.path)
                ObjFsBytesIterator(bytes, size)
            }
        }

        // readUtf8Chunks(size: Int = 65536) -> Iterator<String>
        addFnDoc(
            name = "readUtf8Chunks",
            doc = "Read UTF-8 text in fixed-size chunks as an iterator of `String`.",
            params = listOf(ParamDoc("size", type("lyng.Int"))),
            returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val self = this.thisObj as ObjPath
                val size = args.list.getOrNull(0)?.toInt() ?: 65536
                val text = self.secured.readUtf8(self.path)
                ObjFsStringChunksIterator(text, size)
            }
        }

        // lines() -> Iterator<String>, implemented via readUtf8Chunks
        addFnDoc(
            name = "lines",
            doc = "Iterate lines of the file as `String` values.",
            returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String"))),
            moduleName = module.packageName
        ) {
            fsGuard {
                val chunkIt = thisObj.invokeInstanceMethod(this, "readUtf8Chunks")
                ObjFsLinesIterator(chunkIt)
            }
        }
    }

    // Export into the module scope with docs
    module.addConstDoc(
        name = "Path",
        value = pathType,
        doc = "Filesystem path class. Construct with a string: `Path(\"/tmp\")`.",
        type = type("Path"),
        moduleName = module.packageName
    )
    // Alias as requested (Path(s) style)
    module.addConstDoc(
        name = "Paths",
        value = pathType,
        doc = "Alias of `Path` for those who prefer plural form.",
        type = type("Path"),
        moduleName = module.packageName
    )
}

// --- Helper classes and utilities ---

private fun parsePathArg(scope: Scope, self: ObjPath, arg: Obj): LyngPath {
    return when (arg) {
        is ObjString -> arg.value.toPath()
        is ObjPath -> arg.path
        else -> scope.raiseIllegalArgument("expected Path or String argument")
    }
}

// Map Fs access denials to Lyng runtime exceptions for script-friendly errors
private suspend inline fun Scope.fsGuard(crossinline block: suspend () -> Obj): Obj {
    return try {
        block()
    } catch (e: AccessDeniedException) {
        raiseError(ObjIllegalOperationException(this, e.reasonDetail ?: "access denied"))
    }
}

/** Kotlin-side instance backing the Lyng class `Path`. */
class ObjPath(
    private val klass: ObjClass,
    val secured: LyngFs,
    val path: LyngPath,
) : Obj() {
    // Cache for metadata to avoid repeated FS calls within the same object instance usage
    private var _metadata: net.sergeych.lyngio.fs.LyngMetadata? = null

    override val objClass: ObjClass get() = klass
    override fun toString(): String = path.toString()

    suspend fun ensureMetadata(): net.sergeych.lyngio.fs.LyngMetadata {
        val cached = _metadata
        if (cached != null) return cached
        val m = secured.metadata(path)
        _metadata = m
        return m
    }
}

/** Iterator over byte chunks as Buffers. */
class ObjFsBytesIterator(
    private val data: ByteArray,
    private val chunkSize: Int,
) : Obj() {
    private var pos = 0

    override val objClass: ObjClass = BytesIteratorType

    companion object {
        val BytesIteratorType = object : ObjClass("BytesIterator", ObjIterator) {
            init {
                // make it usable in for-loops
                addFnDoc(
                    name = "iterator",
                    doc = "Return this iterator instance (enables `for` loops).",
                    returns = type("BytesIterator"),
                    moduleName = "lyng.io.fs"
                ) { thisObj }
                addFnDoc(
                    name = "hasNext",
                    doc = "Whether there is another chunk available.",
                    returns = type("lyng.Bool"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsBytesIterator>()
                    (self.pos < self.data.size).toObj()
                }
                addFnDoc(
                    name = "next",
                    doc = "Return the next chunk as a `Buffer`.",
                    returns = type("lyng.Buffer"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsBytesIterator>()
                    if (self.pos >= self.data.size) raiseIllegalState("iterator exhausted")
                    val end = minOf(self.pos + self.chunkSize, self.data.size)
                    val chunk = self.data.copyOfRange(self.pos, end)
                    self.pos = end
                    ObjBuffer(chunk.asUByteArray())
                }
                addFnDoc(
                    name = "cancelIteration",
                    doc = "Stop the iteration early; subsequent `hasNext` returns false.",
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsBytesIterator>()
                    self.pos = self.data.size
                    ObjVoid
                }
            }
        }
    }
}

/** Iterator over utf-8 text chunks (character-counted chunks). */
class ObjFsStringChunksIterator(
    private val text: String,
    private val chunkChars: Int,
) : Obj() {
    private var pos = 0

    override val objClass: ObjClass = StringChunksIteratorType

    companion object {
        val StringChunksIteratorType = object : ObjClass("StringChunksIterator", ObjIterator) {
            init {
                // make it usable in for-loops
                addFnDoc(
                    name = "iterator",
                    doc = "Return this iterator instance (enables `for` loops).",
                    returns = type("StringChunksIterator"),
                    moduleName = "lyng.io.fs"
                ) { thisObj }
                addFnDoc(
                    name = "hasNext",
                    doc = "Whether there is another chunk available.",
                    returns = type("lyng.Bool"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsStringChunksIterator>()
                    (self.pos < self.text.length).toObj()
                }
                addFnDoc(
                    name = "next",
                    doc = "Return the next UTF-8 chunk as a `String`.",
                    returns = type("lyng.String"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsStringChunksIterator>()
                    if (self.pos >= self.text.length) raiseIllegalState("iterator exhausted")
                    val end = minOf(self.pos + self.chunkChars, self.text.length)
                    val chunk = self.text.substring(self.pos, end)
                    self.pos = end
                    ObjString(chunk)
                }
                addFnDoc(
                    name = "cancelIteration",
                    doc = "Stop the iteration early; subsequent `hasNext` returns false.",
                    moduleName = "lyng.io.fs"
                ) { ObjVoid }
            }
        }
    }
}

/** Iterator that yields lines using an underlying chunks iterator. */
class ObjFsLinesIterator(
    private val chunksIterator: Obj,
) : Obj() {
    private var buffer: String = ""
    private var exhausted = false

    override val objClass: ObjClass = LinesIteratorType

    companion object {
        val LinesIteratorType = object : ObjClass("LinesIterator", ObjIterator) {
            init {
                // make it usable in for-loops
                addFnDoc(
                    name = "iterator",
                    doc = "Return this iterator instance (enables `for` loops).",
                    returns = type("LinesIterator"),
                    moduleName = "lyng.io.fs"
                ) { thisObj }
                addFnDoc(
                    name = "hasNext",
                    doc = "Whether another line is available.",
                    returns = type("lyng.Bool"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsLinesIterator>()
                    self.ensureBufferFilled(this)
                    (self.buffer.isNotEmpty() || !self.exhausted).toObj()
                }
                addFnDoc(
                    name = "next",
                    doc = "Return the next line as `String`.",
                    returns = type("lyng.String"),
                    moduleName = "lyng.io.fs"
                ) {
                    val self = thisAs<ObjFsLinesIterator>()
                    self.ensureBufferFilled(this)
                    if (self.buffer.isEmpty() && self.exhausted) raiseIllegalState("iterator exhausted")
                    val idx = self.buffer.indexOf('\n')
                    val line = if (idx >= 0) {
                        val l = self.buffer.substring(0, idx)
                        self.buffer = self.buffer.substring(idx + 1)
                        l
                    } else {
                        // last line without trailing newline
                        val l = self.buffer
                        self.buffer = ""
                        self.exhausted = true
                        l
                    }
                    ObjString(line)
                }
                addFnDoc(
                    name = "cancelIteration",
                    doc = "Stop the iteration early; subsequent `hasNext` returns false.",
                    moduleName = "lyng.io.fs"
                ) { ObjVoid }
            }
        }
    }

    private suspend fun ensureBufferFilled(scope: Scope) {
        if (buffer.contains('\n') || exhausted) return
        // Pull next chunk from the underlying iterator
        val it = chunksIterator.invokeInstanceMethod(scope, "iterator")
        val hasNext = it.invokeInstanceMethod(scope, "hasNext").toBool()
        if (!hasNext) {
            exhausted = true
            return
        }
        val next = it.invokeInstanceMethod(scope, "next")
        buffer += next.toString()
    }
}
