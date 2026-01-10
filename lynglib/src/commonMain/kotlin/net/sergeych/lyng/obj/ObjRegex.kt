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

package net.sergeych.lyng.obj

import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.RegexCache
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.*

class ObjRegex(val regex: Regex) : Obj() {
    override val objClass get() = type

    override suspend fun operatorMatch(scope: Scope, other: Obj): Obj {
        return regex.find(other.cast<ObjString>(scope).value)?.let {
            scope.addOrUpdateItem("$~", ObjRegexMatch(it))
            ObjTrue
        } ?: ObjFalse
    }

    fun find(s: ObjString): Obj =
        regex.find(s.value)?.let { ObjRegexMatch(it) } ?: ObjNull

    companion object {
        val type by lazy {
            object : ObjClass("Regex") {
                override suspend fun callOn(scope: Scope): Obj {
                    val pattern = scope.requireOnlyArg<ObjString>().value
                    val re = if (PerfFlags.REGEX_CACHE) RegexCache.get(pattern) else pattern.toRegex()
                    return ObjRegex(re)
                }
            }.apply {
                addFnDoc(
                    name = "matches",
                    doc = "Whether the entire string matches this regular expression.",
                    params = listOf(ParamDoc("text", type("lyng.String"))),
                    returns = type("lyng.Bool"),
                    moduleName = "lyng.stdlib"
                ) {
                    ObjBool(args.firstAndOnly().toString().matches(thisAs<ObjRegex>().regex))
                }
                addFnDoc(
                    name = "find",
                    doc = "Find the first match in the given string.",
                    params = listOf(ParamDoc("text", type("lyng.String"))),
                    returns = type("lyng.RegexMatch", nullable = true),
                    moduleName = "lyng.stdlib"
                ) {
                    thisAs<ObjRegex>().find(requireOnlyArg<ObjString>())
                }
                addFnDoc(
                    name = "findAll",
                    doc = "Find all matches in the given string.",
                    params = listOf(ParamDoc("text", type("lyng.String"))),
                    returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.RegexMatch"))),
                    moduleName = "lyng.stdlib"
                ) {
                    val s = requireOnlyArg<ObjString>().value
                    ObjList(thisAs<ObjRegex>().regex.findAll(s).map { ObjRegexMatch(it) }.toMutableList())
                }
            }
        }
    }
}

class ObjRegexMatch(val match: MatchResult) : Obj() {
    override val objClass get() = type

    val objGroups: ObjList by lazy {
        // Use groupValues so that index 0 is the whole match and subsequent indices are capturing groups,
        // which matches the language/tests expectation for `$~[i]`.
        ObjList(
            match.groupValues.map { ObjString(it) }.toMutableList()
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

    override suspend fun defaultToString(scope: Scope): ObjString {
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
                addPropertyDoc(
                    name = "groups",
                    doc = "List of captured groups with index 0 as the whole match.",
                    type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.String"))),
                    moduleName = "lyng.stdlib",
                    getter = { thisAs<ObjRegexMatch>().objGroups }
                )
                addPropertyDoc(
                    name = "value",
                    doc = "The matched substring.",
                    type = type("lyng.String"),
                    moduleName = "lyng.stdlib",
                    getter = { thisAs<ObjRegexMatch>().objValue }
                )
                addPropertyDoc(
                    name = "range",
                    doc = "Range of the match in the input (end-exclusive).",
                    type = type("lyng.Range"),
                    moduleName = "lyng.stdlib",
                    getter = { thisAs<ObjRegexMatch>().objRange }
                )
            }
        }
    }
}