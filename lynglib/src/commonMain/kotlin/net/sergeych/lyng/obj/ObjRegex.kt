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

class ObjRegex(val regex: Regex) : Obj() {
    override val objClass = type

    override suspend fun operatorMatch(scope: Scope, other: Obj): Obj {
        return regex.find(other.cast<ObjString>(scope).value)?.let {
            scope.addConst("$~", ObjRegexMatch(it))
            ObjTrue
        } ?: ObjFalse
    }

    fun find(s: ObjString): Obj =
        regex.find(s.value)?.let { ObjRegexMatch(it) } ?: ObjNull

    companion object {
        val type by lazy {
            object : ObjClass("Regex") {
                override suspend fun callOn(scope: Scope): Obj {
                    return ObjRegex(
                        scope.requireOnlyArg<ObjString>().value.toRegex()
                    )
                }
            }.apply {
                addFn("matches") {
                    ObjBool(args.firstAndOnly().toString().matches(thisAs<ObjRegex>().regex))
                }
                addFn("find") {
                    thisAs<ObjRegex>().find(requireOnlyArg<ObjString>())
                }
                addFn("findAll") {
                    val s = requireOnlyArg<ObjString>().value
                    ObjList(thisAs<ObjRegex>().regex.findAll(s).map { ObjRegexMatch(it) }.toMutableList())
                }
            }
        }
    }
}

class ObjRegexMatch(val match: MatchResult) : Obj() {
    override val objClass = type

    val objGroups: ObjList by lazy {
        ObjList(
            match.groups.map { it?.let { ObjString(it.value) } ?: ObjNull }.toMutableList()
        )
    }

    val objValue by lazy { ObjString(match.value) }

    val objRange: ObjRange by lazy {
        val r = match.range

        ObjRange(
            ObjInt(r.first.toLong()),
            ObjInt(r.last.toLong()),
            false
        )
    }

    override suspend fun toString(scope: Scope,calledFromLyng: Boolean): ObjString {
        return ObjString("RegexMath(${objRange.toString(scope)},${objGroups.toString(scope)})")
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return objGroups.getAt(scope, index)
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other === this) return 0
        return -2
    }

    companion object {
        val type by lazy {
            object : ObjClass("RegexMatch") {
                override suspend fun callOn(scope: Scope): Obj {
                    scope.raiseError("RegexMatch can't be constructed directly")
                }
            }.apply {
                addFn("groups") {
                    thisAs<ObjRegexMatch>().objGroups
                }
                addFn("value") {
                    thisAs<ObjRegexMatch>().objValue
                }
                addFn("range") {
                    thisAs<ObjRegexMatch>().objRange
                }
            }
        }
    }
}