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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.Statement
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

/**
 * Abstract class that must provide `iterator` method that returns [ObjIterator] instance.
 */
val ObjIterable by lazy {
    ObjClass("Iterable").apply {

        addPropertyDoc(
            name = "toList",
            doc = "Collect elements of this iterable into a new list.",
            type = type("lyng.List"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val result = mutableListOf<Obj>()
                    val it = scp.thisObj.invokeInstanceMethod(scp, "iterator")
                    while (it.invokeInstanceMethod(scp, "hasNext").toBool()) {
                        result.add(it.invokeInstanceMethod(scp, "next"))
                    }
                    return ObjList(result)
                }
            }
        )

        // it is not effective, but it is open:
        addFnDoc(
            name = "contains",
            doc = "Whether the iterable contains the given element (by equality).",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val obj = scp.args.firstAndOnly()
                    val it = scp.thisObj.invokeInstanceMethod(scp, "iterator")
                    while (it.invokeInstanceMethod(scp, "hasNext").toBool()) {
                        if (obj.equals(scp, it.invokeInstanceMethod(scp, "next")))
                            return ObjTrue
                    }
                    return ObjFalse
                }
            }
        )

        addFnDoc(
            name = "indexOf",
            doc = "Index of the first occurrence of the given element, or -1 if not found.",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Int"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val obj = scp.args.firstAndOnly()
                    var index = 0
                    val it = scp.thisObj.invokeInstanceMethod(scp, "iterator")
                    while (it.invokeInstanceMethod(scp, "hasNext").toBool()) {
                        if (obj.equals(scp, it.invokeInstanceMethod(scp, "next")))
                            return ObjInt(index.toLong())
                        index++
                    }
                    return ObjInt(-1L)
                }
            }
        )

        addPropertyDoc(
            name = "toSet",
            doc = "Collect elements of this iterable into a new set.",
            type = type("lyng.Set"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    return if( scp.thisObj.isInstanceOf(ObjSet.type) )
                        scp.thisObj
                    else {
                        val result = mutableSetOf<Obj>()
                        val it = scp.thisObj.invokeInstanceMethod(scp, "iterator")
                        while (it.invokeInstanceMethod(scp, "hasNext").toBool()) {
                            result.add(it.invokeInstanceMethod(scp, "next"))
                        }
                        ObjSet(result)
                    }
                }
            }
        )

        addPropertyDoc(
            name = "toMap",
            doc = "Collect pairs into a map using [0] as key and [1] as value for each element.",
            type = type("lyng.Map"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val result = mutableMapOf<Obj, Obj>()
                    scp.thisObj.enumerate(scp, object : EnumerateCallback {
                        override suspend fun call(pair: Obj): Boolean {
                            when (pair) {
                                is ObjMapEntry -> result[pair.key] = pair.value
                                else -> result[pair.getAt(scp, ObjInt(0))] = pair.getAt(scp, ObjInt(1))
                            }
                            return true
                        }
                    })
                    return ObjMap(result)
                }
            }
        )

        addFnDoc(
            name = "associateBy",
            doc = "Build a map from elements using the lambda result as key.",
            params = listOf(ParamDoc("keySelector")),
            returns = type("lyng.Map"),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val association = scp.requireOnlyArg<Statement>()
                    val result = ObjMap()
                    scp.thisObj.toFlow(scp).collect {
                        result.map[association.invokeCallable(scp, scp.thisObj, it)] = it
                    }
                    return result
                }
            }
        )

        addFnDoc(
            name = "forEach",
            doc = "Apply the lambda to each element in iteration order.",
            params = listOf(ParamDoc("action")),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val it = scp.thisObj.invokeInstanceMethod(scp, "iterator")
                    val fn = scp.requiredArg<Statement>(0)
                    while (it.invokeInstanceMethod(scp, "hasNext").toBool()) {
                        val x = it.invokeInstanceMethod(scp, "next")
                        fn.execute(scp.createChildScope(Arguments(listOf(x))))
                    }
                    return ObjVoid
                }
            }
        )

        addFnDoc(
            name = "map",
            doc = "Transform elements by applying the given lambda.",
            params = listOf(ParamDoc("transform")),
            returns = type("lyng.List"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val fn = scp.requiredArg<Statement>(0)
                    val result = mutableListOf<Obj>()
                    scp.thisObj.toFlow(scp).collect {
                        result.add(fn.invokeCallable(scp, scp.thisObj, it))
                    }
                    return ObjList(result)
                }
            }
        )

        addFnDoc(
            name = "mapNotNull",
            doc = "Transform elements by applying the given lambda unless it returns null.",
            params = listOf(ParamDoc("transform")),
            returns = type("lyng.List"),
            isOpen = true,
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val fn = scp.requiredArg<Statement>(0)
                    val result = mutableListOf<Obj>()
                    scp.thisObj.toFlow(scp).collect {
                        val transformed = fn.invokeCallable(scp, scp.thisObj, it)
                        if( transformed != ObjNull) result.add(transformed)
                    }
                    return ObjList(result)
                }
            }
        )

        addFnDoc(
            name = "take",
            doc = "Take the first N elements and return them as a list.",
            params = listOf(ParamDoc("n", type("lyng.Int"))),
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    var n = scp.requireOnlyArg<ObjInt>().value.toInt()
                    val result = mutableListOf<Obj>()
                    if (n > 0) {
                        scp.thisObj.enumerate(scp, object : EnumerateCallback {
                            override suspend fun call(element: Obj): Boolean {
                                result.add(element)
                                return --n > 0
                            }
                        })
                    }
                    return ObjList(result)
                }
            }
        )

        addPropertyDoc(
            name = "isEmpty",
            doc = "Whether the iterable has no elements.",
            type = type("lyng.Bool"),
            moduleName = "lyng.stdlib",
            getter = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    return ObjBool(
                        scp.thisObj.invokeInstanceMethod(scp, "iterator")
                            .invokeInstanceMethod(scp, "hasNext").toBool()
                            .not()
                    )
                }
            }
        )

        addFnDoc(
            name = "sortedWith",
            doc = "Return a new list sorted using the provided comparator `(a, b) -> Int`.",
            params = listOf(ParamDoc("comparator")),
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val list = scp.thisObj.callMethod<ObjList>(scp, "toList")
                    val comparator = scp.requireOnlyArg<Statement>()
                    list.quicksort { a, b ->
                        comparator.invokeCallable(scp, scp.thisObj, a, b).toInt()
                    }
                    return list
                }
            }
        )

        addFnDoc(
            name = "reversed",
            doc = "Return a new list with elements in reverse order.",
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib",
            code = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val list = scp.thisObj.callMethod<ObjList>(scp, "toList")
                    list.list.reverse()
                    return list
                }
            }
        )
    }
}