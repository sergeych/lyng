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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Statement

/**
 * Abstract class that must provide `iterator` method that returns [ObjIterator] instance.
 */
val ObjIterable by lazy {
    ObjClass("Iterable").apply {

        addFn("toList") {
            val result = mutableListOf<Obj>()
            val iterator = thisObj.invokeInstanceMethod(this, "iterator")

            while (iterator.invokeInstanceMethod(this, "hasNext").toBool())
                result += iterator.invokeInstanceMethod(this, "next")
            ObjList(result)
        }

        // it is not effective, but it is open:
        addFn("contains", isOpen = true) {
            val obj = args.firstAndOnly()
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.compareTo(this, it.invokeInstanceMethod(this, "next")) == 0)
                    return@addFn ObjTrue
            }
            ObjFalse
        }

        addFn("indexOf", isOpen = true) {
            val obj = args.firstAndOnly()
            var index = 0
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.compareTo(this, it.invokeInstanceMethod(this, "next")) == 0)
                    return@addFn ObjInt(index.toLong())
                index++
            }
            ObjInt(-1L)
        }

        addFn("toSet") {
            if( thisObj.isInstanceOf(ObjSet.type) )
                thisObj
            else {
                val result = mutableSetOf<Obj>()
                val it = thisObj.invokeInstanceMethod(this, "iterator")
                while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                    result += it.invokeInstanceMethod(this, "next")
                }
                ObjSet(result)
            }
        }

        addFn("toMap") {
            val result = ObjMap()
            thisObj.toFlow(this).collect { pair ->
                result.map[pair.getAt(this, 0)] = pair.getAt(this, 1)
            }
            result
        }

        addFn("associateBy") {
            val association = requireOnlyArg<Statement>()
            val result = ObjMap()
            thisObj.toFlow(this).collect {
                result.map[association.call(this, it)] = it
            }
            result
        }

        addFn("forEach", isOpen = true) {
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            val fn = requiredArg<Statement>(0)
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                val x = it.invokeInstanceMethod(this, "next")
                fn.execute(this.copy(Arguments(listOf(x))))
            }
            ObjVoid
        }

        addFn("map", isOpen = true) {
            val fn = requiredArg<Statement>(0)
            val result = mutableListOf<Obj>()
            thisObj.toFlow(this).collect {
                result += fn.call(this, it)
            }
            ObjList(result)
        }

        addFn("take") {
            var n = requireOnlyArg<ObjInt>().value.toInt()
            val result = mutableListOf<Obj>()
            if (n > 0) {
                thisObj.enumerate(this) {
                    result += it
                    --n > 0
                }
            }
            ObjList(result)
        }

        addFn("isEmpty") {
            ObjBool(
                thisObj.invokeInstanceMethod(this, "iterator")
                    .invokeInstanceMethod(this, "hasNext").toBool()
                    .not()
            )
        }

        addFn("sortedWith") {
            val list = thisObj.callMethod<ObjList>(this, "toList")
            val comparator = requireOnlyArg<Statement>()
            list.quicksort { a, b ->
                comparator.call(this, a, b).toInt()
            }
            list
        }

        addFn("reversed") {
            val list = thisObj.callMethod<ObjList>(this, "toList")
            list.list.reverse()
            list
        }
    }
}