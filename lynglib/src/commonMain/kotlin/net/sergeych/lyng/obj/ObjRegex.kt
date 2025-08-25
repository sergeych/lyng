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

    companion object {
        val type by lazy {
            object : ObjClass("Regex") {
                override suspend fun callOn(scope: Scope): Obj {
                    println(scope.requireOnlyArg<ObjString>().value)
                    return ObjRegex(
                        scope.requireOnlyArg<ObjString>().value.toRegex()
                    )
                }
            }.apply {
                addFn("matches") {
                    ObjBool(args.firstAndOnly().toString().matches(thisAs<ObjRegex>().regex))
                }
                addFn("find") {
                    val s = requireOnlyArg<ObjString>().value
                    thisAs<ObjRegex>().regex.find(s)?.let { ObjRegexMatch(it) } ?: ObjNull
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