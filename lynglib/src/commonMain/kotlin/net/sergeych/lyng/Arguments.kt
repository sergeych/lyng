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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjList

data class ParsedArgument(val value: Statement, val pos: Pos, val isSplat: Boolean = false)

suspend fun Collection<ParsedArgument>.toArguments(scope: Scope, tailBlockMode: Boolean): Arguments {
    val list = mutableListOf<Obj>()

    for (x in this) {
        val value = x.value.execute(scope)
        if (x.isSplat) {
            when {
                value is ObjList -> {
                    for (subitem in value.list) list.add(subitem)
                }

                value.isInstanceOf(ObjIterable) -> {
                    val i = (value.invokeInstanceMethod(scope, "toList") as ObjList).list
                    i.forEach { list.add(it) }
                }

                else -> scope.raiseClassCastError("expected list of objects for splat argument")
            }
        } else
            list.add(value)
    }
    return Arguments(list,tailBlockMode)
}

data class Arguments(val list: List<Obj>, val tailBlockMode: Boolean = false) : List<Obj> by list {

    constructor(vararg values: Obj) : this(values.toList())

    fun firstAndOnly(pos: Pos = Pos.UNKNOWN): Obj {
        if (list.size != 1) throw ScriptError(pos, "expected one argument, got ${list.size}")
        return list.first().byValueCopy()
    }

    /**
     * Convert to list of kotlin objects, see [Obj.toKotlin].
     */
    suspend fun toKotlinList(scope: Scope): List<Any?> {
        return list.map { it.toKotlin(scope) }
    }

    fun inspect(): String = list.joinToString(", ") { it.inspect() }

    companion object {
        val EMPTY = Arguments(emptyList())
        fun from(values: Collection<Obj>) = Arguments(values.toList())
    }
}

