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
            moduleName = "lyng.stdlib"
        ) { ObjArrayIterator(thisObj).also { it.init(requireScope()) } }

        addFnDoc(
            name = "contains",
            doc = "Whether the array contains the given element (by equality).",
            params = listOf(ParamDoc("element")),
            returns = type("lyng.Bool"),
            isOpen = true,
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val obj = args.firstAndOnly()
            for (i in 0..<thisObj.invokeInstanceMethod(scope, "size").toInt()) {
                if (thisObj.getAt(scope, ObjInt(i.toLong())).compareTo(scope, obj) == 0) return@addFnDoc ObjTrue
            }
            ObjFalse
        }

        addPropertyDoc(
            name = "last",
            doc = "The last element of this array.",
            type = type("lyng.Any"),
            moduleName = "lyng.stdlib",
            getter = {
                val scope = requireScope()
                this.thisObj.invokeInstanceMethod(
                    scope,
                    "getAt",
                    (this.thisObj.invokeInstanceMethod(scope, "size").toInt() - 1).toObj()
                )
            }
        )

        addPropertyDoc(
            name = "lastIndex",
            doc = "Index of the last element (size - 1).",
            type = type("lyng.Int"),
            moduleName = "lyng.stdlib",
            getter = {
                val scope = requireScope()
                (this.thisObj.invokeInstanceMethod(scope, "size").toInt() - 1).toObj()
            }
        )

        addPropertyDoc(
            name = "indices",
            doc = "Range of valid indices for this array.",
            type = type("lyng.Range"),
            moduleName = "lyng.stdlib",
            getter = {
                val scope = requireScope()
                ObjRange(0.toObj(), this.thisObj.invokeInstanceMethod(scope, "size"), false)
            }
        )

        addFnDoc(
            name = "binarySearch",
            doc = "Binary search for a target in a sorted array. Returns index or negative insertion point - 1.",
            params = listOf(ParamDoc("target")),
            returns = type("lyng.Int"),
            moduleName = "lyng.stdlib"
        ) {
            val scope = requireScope()
            val target = args.firstAndOnly()
            var low = 0
            var high = thisObj.invokeInstanceMethod(scope, "size").toInt() - 1

            while (low <= high) {
                val mid = (low + high) / 2
                val midVal = thisObj.getAt(scope, ObjInt(mid.toLong()))

                val cmp = midVal.compareTo(scope, target)
                when {
                    cmp == 0 -> return@addFnDoc (mid).toObj()
                    cmp > 0 -> high = mid - 1
                    else -> low = mid + 1
                }
            }

            (-low - 1).toObj()
        }
    }
}
