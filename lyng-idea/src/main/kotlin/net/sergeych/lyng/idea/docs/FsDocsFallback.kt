/*
 * Minimal fallback docs seeding for `lyng.io.fs` used only inside the IDEA plugin
 * when external docs module (lyngio) is not present on the classpath.
 *
 * We keep it tiny and plugin-local to avoid coupling core library to external packages.
 */
package net.sergeych.lyng.idea.docs

import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.type

internal object FsDocsFallback {
    @Volatile
    private var seeded = false

    fun ensureOnce(): Boolean {
        if (seeded) return true
        synchronized(this) {
            if (seeded) return true
            BuiltinDocRegistry.module("lyng.io.fs") {
                // Class Path summary and a few commonly used methods
                classDoc(name = "Path", doc = "Filesystem path class. Construct with a string: `Path(\"/tmp\")`.") {
                    method(name = "exists", doc = "Whether the path exists on the filesystem.", returns = type("lyng.Bool"))
                    method(name = "isFile", doc = "Whether the path exists and is a file.", returns = type("lyng.Bool"))
                    method(name = "isDir", doc = "Whether the path exists and is a directory.", returns = type("lyng.Bool"))
                    method(name = "readUtf8", doc = "Read the entire file as UTF-8 string.", returns = type("lyng.String"))
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
                valDoc(name = "Path", doc = "Filesystem path class. Construct with a string: `Path(\"/tmp\")`.", type = type("Path"))
                valDoc(name = "Paths", doc = "Alias of `Path` for those who prefer plural form.", type = type("Path"))
            }
            seeded = true
            return true
        }
    }
}
