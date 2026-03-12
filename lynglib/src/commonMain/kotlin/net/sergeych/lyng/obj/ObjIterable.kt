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
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

/**
 * Abstract class that must provide `iterator` method that returns [ObjIterator] instance.
 */
val ObjIterable by lazy {
    ObjClass("Iterable").apply {
        addFn(
            name = "iterator",
            isAbstract = true,
            isClosed = false,
            code = null
        )

        addFnDoc(
            name = "toList",
            doc = "Collect elements of this iterable into a new list.",
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val result = mutableListOf<Obj>()
            val it = thisObj.invokeInstanceMethod(scope, "iterator")
            while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                result.add(it.invokeInstanceMethod(scope, "next"))
            }
            ObjList(result)
        }

        addFnDoc(
            name = "toImmutableList",
            doc = "Collect elements of this iterable into a new immutable list.",
            returns = type("lyng.ImmutableList"),
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val result = mutableListOf<Obj>()
            val it = thisObj.invokeInstanceMethod(scope, "iterator")
            while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                result.add(it.invokeInstanceMethod(scope, "next"))
            }
            ObjImmutableList(result)
        }

        // it is not effective, but it is open:
        addFnDoc(
            name = "contains",
            doc = "Whether the iterable contains the given element (by equality).",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val obj = args.firstAndOnly()
            val it = thisObj.invokeInstanceMethod(scope, "iterator")
            while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                if (obj.equals(scope, it.invokeInstanceMethod(scope, "next")))
                    return@addFnDoc ObjTrue
            }
            ObjFalse
        }

        addFnDoc(
            name = "indexOf",
            doc = "Index of the first occurrence of the given element, or -1 if not found.",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Int"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val obj = args.firstAndOnly()
            var index = 0
            val it = thisObj.invokeInstanceMethod(scope, "iterator")
            while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                if (obj.equals(scope, it.invokeInstanceMethod(scope, "next")))
                    return@addFnDoc ObjInt(index.toLong())
                index++
            }
            ObjInt(-1L)
        }

        addPropertyDoc(
            name = "toSet",
            doc = "Collect elements of this iterable into a new set.",
            type = type("lyng.Set"),
            moduleName = "lyng.stdlib",
            getter = {
                if( this.thisObj.isInstanceOf(ObjSet.type) )
                    this.thisObj
                else {
                    val result = mutableSetOf<Obj>()
                    val scope = requireScope()
                    val it = this.thisObj.invokeInstanceMethod(scope, "iterator")
                    while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                        result.add(it.invokeInstanceMethod(scope, "next"))
                    }
                    ObjSet(result)
                }
            }
        )

        addPropertyDoc(
            name = "toImmutableSet",
            doc = "Collect elements of this iterable into a new immutable set.",
            type = type("lyng.ImmutableSet"),
            moduleName = "lyng.stdlib",
            getter = {
                when (val self = this.thisObj) {
                    is ObjImmutableSet -> self
                    is ObjSet -> ObjImmutableSet(self.set)
                    else -> {
                        val result = mutableSetOf<Obj>()
                        val scope = requireScope()
                        val it = self.invokeInstanceMethod(scope, "iterator")
                        while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                            result.add(it.invokeInstanceMethod(scope, "next"))
                        }
                        ObjImmutableSet(result)
                    }
                }
            }
        )

        addPropertyDoc(
            name = "toMap",
            doc = "Collect pairs into a map using [0] as key and [1] as value for each element.",
            type = type("lyng.Map"),
            moduleName = "lyng.stdlib",
            getter = {
                val result = mutableMapOf<Obj, Obj>()
                val scope = requireScope()
                this.thisObj.enumerate(scope) { pair ->
                    when (pair) {
                        is ObjMapEntry -> result[pair.key] = pair.value
                        else -> result[pair.getAt(scope, 0)] = pair.getAt(scope, 1)
                    }
                    true
                }
                ObjMap(result)
            }
        )

        addPropertyDoc(
            name = "toImmutableMap",
            doc = "Collect pairs into an immutable map using [0] as key and [1] as value for each element.",
            type = type("lyng.ImmutableMap"),
            moduleName = "lyng.stdlib",
            getter = {
                val result = linkedMapOf<Obj, Obj>()
                val scope = requireScope()
                this.thisObj.enumerate(scope) { pair ->
                    when (pair) {
                        is ObjMapEntry -> result[pair.key] = pair.value
                        else -> result[pair.getAt(scope, 0)] = pair.getAt(scope, 1)
                    }
                    true
                }
                ObjImmutableMap(result)
            }
        )

        addFnDoc(
            name = "associateBy",
            doc = "Build a map from elements using the lambda result as key.",
            params = listOf(ParamDoc("keySelector")),
            returns = type("lyng.Map"),
            moduleName = "lyng.stdlib"
        ) {
            val association = requireOnlyArg<Obj>()
            val result = ObjMap()
            thisObj.toFlow(requireScope()).collect {
                result.map[call(association, Arguments(it))] = it
            }
            result
        }

        addFnDoc(
            name = "forEach",
            doc = "Apply the lambda to each element in iteration order.",
            params = listOf(ParamDoc("action")),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val it = thisObj.invokeInstanceMethod(scope, "iterator")
            val fn = requiredArg<Obj>(0)
            while (it.invokeInstanceMethod(scope, "hasNext").toBool()) {
                val x = it.invokeInstanceMethod(scope, "next")
                call(fn, Arguments(listOf(x)))
            }
            ObjVoid
        }

        addFnDoc(
            name = "map",
            doc = "Transform elements by applying the given lambda.",
            params = listOf(ParamDoc("transform")),
            returns = type("lyng.List"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val fn = requiredArg<Obj>(0)
            val result = mutableListOf<Obj>()
            thisObj.toFlow(requireScope()).collect {
                result.add(call(fn, Arguments(it)))
            }
            ObjList(result)
        }

        addFnDoc(
            name = "mapNotNull",
            doc = "Transform elements by applying the given lambda unless it returns null.",
            params = listOf(ParamDoc("transform")),
            returns = type("lyng.List"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val fn = requiredArg<Obj>(0)
            val result = mutableListOf<Obj>()
            thisObj.toFlow(requireScope()).collect {
                val transformed = call(fn, Arguments(it))
                if( transformed != ObjNull) result.add(transformed)
            }
            ObjList(result)
        }

        addFnDoc(
            name = "take",
            doc = "Take the first N elements and return them as a list.",
            params = listOf(ParamDoc("n", type("lyng.Int"))),
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib"
        ) {
            var n = requireOnlyArg<ObjInt>().value.toInt()
            val result = mutableListOf<Obj>()
            if (n > 0) {
                thisObj.enumerate(requireScope()) {
                    result.add(it)
                    --n > 0
                }
            }
            ObjList(result)
        }

        addPropertyDoc(
            name = "isEmpty",
            doc = "Whether the iterable has no elements.",
            type = type("lyng.Bool"),
            moduleName = "lyng.stdlib",
            getter = {
                ObjBool(
                    this.thisObj.invokeInstanceMethod(requireScope(), "iterator")
                        .invokeInstanceMethod(requireScope(), "hasNext").toBool()
                        .not()
                )
            }
        )

        addFnDoc(
            name = "sortedWith",
            doc = "Return a new list sorted using the provided comparator `(a, b) -> Int`.",
            params = listOf(ParamDoc("comparator")),
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib"
        ) {
            val list = thisObj.callMethod<ObjList>(requireScope(), "toList")
            val comparator = requireOnlyArg<Obj>()
            list.quicksort { a, b ->
                call(comparator, Arguments(a, b)).toInt()
            }
            list
        }

        addFnDoc(
            name = "reversed",
            doc = "Return a new list with elements in reverse order.",
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib"
        ) {
            val list = thisObj.callMethod<ObjList>(requireScope(), "toList")
            list.list.reverse()
            list
        }
    }
}
