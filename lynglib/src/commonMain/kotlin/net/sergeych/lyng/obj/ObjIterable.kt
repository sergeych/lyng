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
            getter = {
                val result = mutableListOf<Obj>()
                val it = this.thisObj.invokeInstanceMethod(this, "iterator")
                while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                    result.add(it.invokeInstanceMethod(this, "next"))
                }
                ObjList(result)
            }
        )

        // it is not effective, but it is open:
        addFnDoc(
            name = "contains",
            doc = "Whether the iterable contains the given element (by equality).",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val obj = args.firstAndOnly()
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.equals(this, it.invokeInstanceMethod(this, "next")))
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
            val obj = args.firstAndOnly()
            var index = 0
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.equals(this, it.invokeInstanceMethod(this, "next")))
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
                    val it = this.thisObj.invokeInstanceMethod(this, "iterator")
                    while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                        result.add(it.invokeInstanceMethod(this, "next"))
                    }
                    ObjSet(result)
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
                this.thisObj.enumerate(this) { pair ->
                    when (pair) {
                        is ObjMapEntry -> result[pair.key] = pair.value
                        else -> result[pair.getAt(this, 0)] = pair.getAt(this, 1)
                    }
                    true
                }
                ObjMap(result)
            }
        )

        addFnDoc(
            name = "associateBy",
            doc = "Build a map from elements using the lambda result as key.",
            params = listOf(ParamDoc("keySelector")),
            returns = type("lyng.Map"),
            moduleName = "lyng.stdlib"
        ) {
            val association = requireOnlyArg<Statement>()
            val result = ObjMap()
            thisObj.toFlow(this).collect {
                result.map[association.call(this, it)] = it
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
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            val fn = requiredArg<Statement>(0)
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                val x = it.invokeInstanceMethod(this, "next")
                fn.execute(this.createChildScope(Arguments(listOf(x))))
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
            val fn = requiredArg<Statement>(0)
            val result = mutableListOf<Obj>()
            thisObj.toFlow(this).collect {
                result.add(fn.call(this, it))
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
            val fn = requiredArg<Statement>(0)
            val result = mutableListOf<Obj>()
            thisObj.toFlow(this).collect {
                val transformed = fn.call(this, it)
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
                thisObj.enumerate(this) {
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
                    this.thisObj.invokeInstanceMethod(this, "iterator")
                        .invokeInstanceMethod(this, "hasNext").toBool()
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
            val list = thisObj.callMethod<ObjList>(this, "toList")
            val comparator = requireOnlyArg<Statement>()
            list.quicksort { a, b ->
                comparator.call(this, a, b).toInt()
            }
            list
        }

        addFnDoc(
            name = "reversed",
            doc = "Return a new list with elements in reverse order.",
            returns = type("lyng.List"),
            moduleName = "lyng.stdlib"
        ) {
            val list = thisObj.callMethod<ObjList>(this, "toList")
            list.list.reverse()
            list
        }
    }
}