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
 * Ensure stdlib Obj*-defined docs (like String methods added via ObjString.addFnDoc)
 * are initialized before registry lookups for completion/quick docs.
 */
package net.sergeych.lyng.miniast

object StdlibDocsBootstrap {
    // Simple idempotent guard; races are harmless as initializer side-effects are idempotent
    private var ensured = false

    fun ensure() {
        if (ensured) return
        try {
            // Touch core Obj* types whose docs are registered via addFnDoc/addConstDoc
            // Accessing .type forces their static initializers to run and register docs.
            @Suppress("UNUSED_VARIABLE")
            val _string = net.sergeych.lyng.obj.ObjString.type
            @Suppress("UNUSED_VARIABLE")
            val _any = net.sergeych.lyng.obj.Obj.rootObjectType
            @Suppress("UNUSED_VARIABLE")
            val _list = net.sergeych.lyng.obj.ObjList.type
            @Suppress("UNUSED_VARIABLE")
            val _map = net.sergeych.lyng.obj.ObjMap.type
            @Suppress("UNUSED_VARIABLE")
            val _int = net.sergeych.lyng.obj.ObjInt.type
            @Suppress("UNUSED_VARIABLE")
            val _real = net.sergeych.lyng.obj.ObjReal.type
            @Suppress("UNUSED_VARIABLE")
            val _bool = net.sergeych.lyng.obj.ObjBool.type
            @Suppress("UNUSED_VARIABLE")
            val _regex = net.sergeych.lyng.obj.ObjRegex.type
            @Suppress("UNUSED_VARIABLE")
            val _range = net.sergeych.lyng.obj.ObjRange.type
            @Suppress("UNUSED_VARIABLE")
            val _buffer = net.sergeych.lyng.obj.ObjBuffer.type

            // Also touch time module types so their docs (moduleName = "lyng.time") are registered
            // This enables completion/quick docs for symbols imported via `import lyng.time` (e.g., Instant, DateTime, Duration)
            try {
                @Suppress("UNUSED_VARIABLE")
                val _instant = net.sergeych.lyng.obj.ObjInstant.type
                @Suppress("UNUSED_VARIABLE")
                val _datetime = net.sergeych.lyng.obj.ObjDateTime.type
                @Suppress("UNUSED_VARIABLE")
                val _duration = net.sergeych.lyng.obj.ObjDuration.type
            } catch (_: Throwable) {
                // Optional; absence should not affect stdlib core
            }
        } catch (_: Throwable) {
            // Best-effort; absence should not break consumers
        } finally {
            ensured = true
        }
    }
}
