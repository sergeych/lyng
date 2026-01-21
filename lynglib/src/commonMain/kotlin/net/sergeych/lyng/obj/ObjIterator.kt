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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type

/**
 * Iterator should provide lyng-level iterator functions:
 *
 * - hasNext()
 * - next()
 * - optional cancelIteration() that _may_ be called when iteration is performed
 *   only on the part of the iterable entity. Implement it when there are resources
 *   to be reclaimed on iteration interruption.
 */
val ObjIterator by lazy {
    ObjClass("Iterator").apply {
        // Base protocol methods; actual iterators override these.
        addFnDoc(
            name = "cancelIteration",
            doc = "Optional hint to stop iteration early and free resources.",
            returns = type("lyng.Void"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = ObjVoid
            }
        )
        addFnDoc(
            name = "hasNext",
            doc = "Whether another element is available.",
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = scp.raiseNotImplemented("hasNext() is not implemented")
            }
        )
        addFnDoc(
            name = "next",
            doc = "Return the next element.",
            returns = type("lyng.Any"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = scp.raiseNotImplemented("next() is not implemented")
            }
        )
        // Helper to consume iterator into a list
        addFnDoc(
            name = "toList",
            doc = "Consume this iterator and collect elements into a list.",
            returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val out = mutableListOf<Obj>()
                    while (true) {
                        val has = scp.thisObj.invokeInstanceMethod(scp, "hasNext").toBool()
                        if (!has) break
                        val v = scp.thisObj.invokeInstanceMethod(scp, "next")
                        out += v
                    }
                    return ObjList(out.toMutableList())
                }
            }
        )
    }
}

