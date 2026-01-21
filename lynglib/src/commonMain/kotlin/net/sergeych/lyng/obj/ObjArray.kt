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
import net.sergeych.lyng.miniast.*

val ObjArray by lazy {

    /**
     * Array abstract class is a [ObjCollection] with `getAt` method.
     */
    ObjClass("Array", ObjCollection).apply {
        // we can create iterators using size/getat:

        addFnDoc(
            name = "iterator",
            doc = "Iterator over elements of this array using its indexer.",
            returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = ObjArrayIterator(scp.thisObj).also { it.init(scp) }
            }
        )

        addFnDoc(
            name = "contains",
            doc = "Whether the array contains the given element (by equality).",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val obj = scp.args.firstAndOnly()
                    for (i in 0..<scp.thisObj.invokeInstanceMethod(scp, "size").toInt()) {
                        if (scp.thisObj.getAt(scp, ObjInt(i.toLong())).compareTo(scp, obj) == 0) return ObjTrue
                    }
                    return ObjFalse
                }
            }
        )

        addPropertyDoc(
            name = "last",
            doc = "The last element of this array.",
            type = type("lyng.Any"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    return scp.thisObj.invokeInstanceMethod(
                        scp,
                        "getAt",
                        (scp.thisObj.invokeInstanceMethod(scp, "size").toInt() - 1).toObj()
                    )
                }
            }
        )

        addPropertyDoc(
            name = "lastIndex",
            doc = "Index of the last element (size - 1).",
            type = type("lyng.Int"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = (scp.thisObj.invokeInstanceMethod(scp, "size").toInt() - 1).toObj()
            }
        )

        addPropertyDoc(
            name = "indices",
            doc = "Range of valid indices for this array.",
            type = type("lyng.Range"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj = ObjRange(0.toObj(), scp.thisObj.invokeInstanceMethod(scp, "size"), false)
            }
        )

        addFnDoc(
            name = "binarySearch",
            doc = "Binary search for a target in a sorted array. Returns index or negative insertion point - 1.",
            params = listOf(ParamDoc("target")),
            returns = type("lyng.Int"),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val target = scp.args.firstAndOnly()
                    var low = 0
                    var high = scp.thisObj.invokeInstanceMethod(scp, "size").toInt() - 1

                    while (low <= high) {
                        val mid = (low + high) / 2
                        val midVal = scp.thisObj.getAt(scp, ObjInt(mid.toLong()))

                        val cmp = midVal.compareTo(scp, target)
                        when {
                            cmp == 0 -> return (mid).toObj()
                            cmp > 0 -> high = mid - 1
                            else -> low = mid + 1
                        }
                    }

                    return (-low - 1).toObj()
                }
            }
        )
    }
}