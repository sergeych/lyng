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

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type

class ObjChar(val value: Char): Obj() {

    override val objClass: ObjClass = type

    override suspend fun compareTo(scope: Scope, other: Obj): Int =
        (other as? ObjChar)?.let { value.compareTo(it.value) } ?: -1

    override fun toString(): String = value.toString()

    override suspend fun inspect(scope: Scope): String = "'$value'"

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjChar

        return value == other.value
    }

    companion object {
        val type = ObjClass("Char").apply {
            addFnDoc(
                name = "code",
                doc = "Unicode code point (UTF-16 code unit) of this character.",
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) { ObjInt(thisAs<ObjChar>().value.code.toLong()) }
            addFn("isDigit") {
                thisAs<ObjChar>().value.isDigit().toObj()
            }
            addFn("isSpace") {
                thisAs<ObjChar>().value.isWhitespace().toObj()
            }
        }
    }
}