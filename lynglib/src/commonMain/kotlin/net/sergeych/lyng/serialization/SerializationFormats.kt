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

package net.sergeych.lyng.serialization

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.addConstDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.requireOnlyArg
import net.sergeych.lyng.requireScope

abstract class ObjSerializationFormatClass(
    className: String
) : ObjClass(className) {

    abstract suspend fun encodeValue(scope: Scope, value: Obj): Obj

    abstract suspend fun decodeValue(scope: Scope, encoded: Obj): Obj

    init {
        addClassFn("encode") {
            encodeValue(requireScope(), requireOnlyArg())
        }
        addClassFn("decode") {
            decodeValue(requireScope(), requireOnlyArg())
        }
    }
}

suspend fun ModuleScope.bindSerializationFormat(
    format: ObjSerializationFormatClass,
    exportName: String = format.className,
    doc: String = "${format.className} serialization format."
): ObjSerializationFormatClass {
    addConstDoc(
        name = exportName,
        value = format,
        doc = doc,
        type = type("lyng.Class")
    )
    return format
}
