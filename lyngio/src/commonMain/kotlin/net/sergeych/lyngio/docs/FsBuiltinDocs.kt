/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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
 * Filesystem module builtin docs registration, located in lyngio so core library
 * does not depend on external packages. The IDEA plugin (and any other tooling)
 * may reflectively call FsBuiltinDocs.ensure() to make sure docs are registered.
 */
package net.sergeych.lyngio.docs

import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.type

object FsBuiltinDocs {
    private var registered = false

    fun ensure() {
        if (registered) return
        // Register docs immediately (not lazy) so tooling can see them without executing module builders
        BuiltinDocRegistry.module("lyng.io.fs") {
            // Class Path with a short summary
            classDoc(
                name = "Path",
                doc = "Filesystem path class. Construct with a string: `Path(\"/tmp\")`."
            ) {
                method(
                    name = "name",
                    doc = "Base name of the path (last segment).",
                    returns = type("lyng.String")
                )
                method(
                    name = "parent",
                    doc = "Parent directory as a Path or null if none.",
                    returns = type("Path", nullable = true)
                )
                method(
                    name = "segments",
                    doc = "List of path segments.",
                    returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String")))
                )
                method(
                    name = "exists",
                    doc = "Check whether this path exists on the filesystem.",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "isFile",
                    doc = "True if this path is a regular file (based on cached metadata).",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "isDirectory",
                    doc = "True if this path is a directory (based on cached metadata).",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "size",
                    doc = "File size in bytes, or null when unavailable.",
                    returns = type("lyng.Int", nullable = true)
                )
                method(
                    name = "createdAt",
                    doc = "Creation time as `Instant`, or null when unavailable.",
                    returns = type("lyng.Instant", nullable = true)
                )
                method(
                    name = "createdAtMillis",
                    doc = "Creation time in milliseconds since epoch, or null when unavailable.",
                    returns = type("lyng.Int", nullable = true)
                )
                method(
                    name = "modifiedAt",
                    doc = "Last modification time as `Instant`, or null when unavailable.",
                    returns = type("lyng.Instant", nullable = true)
                )
                method(
                    name = "modifiedAtMillis",
                    doc = "Last modification time in milliseconds since epoch, or null when unavailable.",
                    returns = type("lyng.Int", nullable = true)
                )
                method(
                    name = "list",
                    doc = "List directory entries as `Path` objects.",
                    returns = TypeGenericDoc(type("lyng.List"), listOf(type("Path")))
                )
                method(
                    name = "readBytes",
                    doc = "Read the entire file into a binary buffer.",
                    returns = type("lyng.Buffer")
                )
                method(
                    name = "writeBytes",
                    doc = "Write a binary buffer to the file, replacing content.",
                    params = listOf(ParamDoc("bytes", type("lyng.Buffer")))
                )
                method(
                    name = "appendBytes",
                    doc = "Append a binary buffer to the end of the file.",
                    params = listOf(ParamDoc("bytes", type("lyng.Buffer")))
                )
                method(
                    name = "readUtf8",
                    doc = "Read the entire file as a UTF-8 string.",
                    returns = type("lyng.String")
                )
                method(
                    name = "writeUtf8",
                    doc = "Write a UTF-8 string to the file, replacing content.",
                    params = listOf(ParamDoc("text", type("lyng.String")))
                )
                method(
                    name = "appendUtf8",
                    doc = "Append UTF-8 text to the end of the file.",
                    params = listOf(ParamDoc("text", type("lyng.String")))
                )
                method(
                    name = "metadata",
                    doc = "Fetch cached metadata as a map of fields: `isFile`, `isDirectory`, `size`, `createdAtMillis`, `modifiedAtMillis`, `isSymlink`.",
                    returns = TypeGenericDoc(type("lyng.Map"), listOf(type("lyng.String"), type("lyng.Any")))
                )
                method(
                    name = "mkdirs",
                    doc = "Create directories (like `mkdir -p`). If `mustCreate` is true and the path already exists, the call fails.",
                    params = listOf(ParamDoc("mustCreate", type("lyng.Bool")))
                )
                method(
                    name = "move",
                    doc = "Move this path to a new location. `to` may be a `Path` or `String`.",
                    params = listOf(ParamDoc("to"), ParamDoc("overwrite", type("lyng.Bool")))
                )
                method(
                    name = "delete",
                    doc = "Delete this path. `recursively=true` removes directories with their contents.",
                    params = listOf(ParamDoc("mustExist", type("lyng.Bool")), ParamDoc("recursively", type("lyng.Bool")))
                )
                method(
                    name = "copy",
                    doc = "Copy this path to a new location. `to` may be a `Path` or `String`.",
                    params = listOf(ParamDoc("to"), ParamDoc("overwrite", type("lyng.Bool")))
                )
                method(
                    name = "glob",
                    doc = "List entries matching a glob pattern (no recursion).",
                    params = listOf(ParamDoc("pattern", type("lyng.String"))),
                    returns = TypeGenericDoc(type("lyng.List"), listOf(type("Path")))
                )
                method(
                    name = "readChunks",
                    doc = "Read file in fixed-size chunks as an iterator of `Buffer`.",
                    params = listOf(ParamDoc("size", type("lyng.Int"))),
                    returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Buffer")))
                )
                method(
                    name = "readUtf8Chunks",
                    doc = "Read UTF-8 text in fixed-size chunks as an iterator of `String`.",
                    params = listOf(ParamDoc("size", type("lyng.Int"))),
                    returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String")))
                )
                method(
                    name = "lines",
                    doc = "Iterate lines of the file as `String` values.",
                    returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String")))
                )
            }

            classDoc(
                name = "BytesIterator",
                doc = "Iterator over binary chunks."
            ) {
                method("iterator", "Return this iterator instance.", returns = type("BytesIterator"))
                method("hasNext", "Whether there is another chunk available.", returns = type("lyng.Bool"))
                method("next", "Return the next chunk as a `Buffer`.", returns = type("lyng.Buffer"))
                method("cancelIteration", "Stop the iteration early.")
            }

            classDoc(
                name = "StringChunksIterator",
                doc = "Iterator over UTF-8 text chunks."
            ) {
                method("iterator", "Return this iterator instance.", returns = type("StringChunksIterator"))
                method("hasNext", "Whether there is another chunk available.", returns = type("lyng.Bool"))
                method("next", "Return the next UTF-8 chunk as a `String`.", returns = type("lyng.String"))
                method("cancelIteration", "Stop the iteration early.")
            }

            classDoc(
                name = "LinesIterator",
                doc = "Iterator that yields lines of text."
            ) {
                method("iterator", "Return this iterator instance.", returns = type("LinesIterator"))
                method("hasNext", "Whether another line is available.", returns = type("lyng.Bool"))
                method("next", "Return the next line as `String`.", returns = type("lyng.String"))
                method("cancelIteration", "Stop the iteration early.")
            }

            // Top-level exported constants
            valDoc(
                name = "Path",
                doc = "Filesystem path class. Construct with a string: `Path(\"/tmp\")`.",
                type = type("Path")
            )
            valDoc(
                name = "Paths",
                doc = "Alias of `Path` for those who prefer plural form.",
                type = type("Path")
            )
        }
        registered = true
    }
}
