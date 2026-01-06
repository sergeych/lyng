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
                // Common instance methods (subset sufficient for Quick Docs)
                method(
                    name = "exists",
                    doc = "Whether the path exists on the filesystem.",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "isFile",
                    doc = "Whether the path exists and is a file.",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "isDir",
                    doc = "Whether the path exists and is a directory.",
                    returns = type("lyng.Bool")
                )
                method(
                    name = "readUtf8",
                    doc = "Read the entire file as UTF-8 string.",
                    returns = type("lyng.String")
                )
                method(
                    name = "writeUtf8",
                    doc = "Write UTF-8 string to the file (overwrite).",
                    params = listOf(ParamDoc("text", type("lyng.String")))
                )
                method(
                    name = "bytes",
                    doc = "Iterate file content as `Buffer` chunks.",
                    params = listOf(ParamDoc("size", type("lyng.Int"))),
                    returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Buffer")))
                )
                method(
                    name = "lines",
                    doc = "Iterate file as lines of text.",
                    returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.String")))
                )
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
