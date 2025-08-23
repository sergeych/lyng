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

val ObjArray by lazy {

    /**
     * Array abstract class is a [ObjCollection] with `getAt` method.
     */
    ObjClass("Array", ObjCollection).apply {
        // we can create iterators using size/getat:

        addFn("iterator") {
            ObjArrayIterator(thisObj).also { it.init(this) }
        }

        addFn("contains", isOpen = true) {
            val obj = args.firstAndOnly()
            for (i in 0..<thisObj.invokeInstanceMethod(this, "size").toInt()) {
                if (thisObj.getAt(this, ObjInt(i.toLong())).compareTo(this, obj) == 0) return@addFn ObjTrue
            }
            ObjFalse
        }

        addFn("last") {
            thisObj.invokeInstanceMethod(
                this,
                "getAt",
                (thisObj.invokeInstanceMethod(this, "size").toInt() - 1).toObj()
            )
        }

        addFn("lastIndex") { (thisObj.invokeInstanceMethod(this, "size").toInt() - 1).toObj() }

        addFn("indices") {
            ObjRange(0.toObj(), thisObj.invokeInstanceMethod(this, "size"), false)
        }

        addFn("binarySearch") {
            val target = args.firstAndOnly()
            var low = 0
            var high = thisObj.invokeInstanceMethod(this, "size").toInt() - 1

            while (low <= high) {
                val mid = (low + high) / 2
                val midVal = thisObj.getAt(this, ObjInt(mid.toLong()))

                val cmp = midVal.compareTo(this, target)
                when {
                    cmp == 0 -> return@addFn (mid).toObj()
                    cmp > 0 -> high = mid - 1
                    else -> low = mid + 1
                }
            }

            // Элемент не найден, возвращаем -(точка вставки) - 1
            (-low - 1).toObj()
        }
    }
}