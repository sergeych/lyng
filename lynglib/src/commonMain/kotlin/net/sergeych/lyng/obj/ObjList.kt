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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeCallable
import net.sergeych.lyng.Statement
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjList(val list: MutableList<Obj> = mutableListOf()) : Obj() {

    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (this === other) return true
        if (other !is ObjList) {
            if (other.isInstanceOf(ObjIterable)) {
                return compareTo(scope, other) == 0
            }
            return false
        }
        if (list.size != other.list.size) return false
        for (i in 0..<list.size) {
            if (!list[i].equals(scope, other.list[i])) return false
        }
        return true
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return when (index) {
            is ObjInt -> {
                list[index.toInt()]
            }

            is ObjRange -> {
                when {
                    index.start is ObjInt && index.end is ObjInt -> {
                        if (index.isEndInclusive)
                            ObjList(list.subList(index.start.toInt(), index.end.toInt() + 1).toMutableList())
                        else
                            ObjList(list.subList(index.start.toInt(), index.end.toInt()).toMutableList())
                    }

                    index.isOpenStart && !index.isOpenEnd -> {
                        if (index.isEndInclusive)
                            ObjList(list.subList(0, index.end!!.toInt() + 1).toMutableList())
                        else
                            ObjList(list.subList(0, index.end!!.toInt()).toMutableList())
                    }

                    index.isOpenEnd && !index.isOpenStart -> {
                        ObjList(list.subList(index.start!!.toInt(), list.size).toMutableList())
                    }

                    index.isOpenStart && index.isOpenEnd -> {
                        ObjList(list.toMutableList())
                    }

                    else -> {
                        throw RuntimeException("Can't apply range for index: $index")
                    }
                }
            }

            else -> scope.raiseIllegalArgument("Illegal index object for a list: ${index.inspect(scope)}")
        }
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        list[index.toInt()] = newValue
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other is ObjList) {
            val mySize = list.size
            val otherSize = other.list.size
            val commonSize = minOf(mySize, otherSize)
            for (i in 0..<commonSize) {
                val d = list[i].compareTo(scope, other.list[i])
                if (d != 0) {
                    return d
                }
            }
            val res = mySize.compareTo(otherSize)
            return res
        }
        if (other.isInstanceOf(ObjIterable)) {
            val it1 = this.list.iterator()
            val it2 = other.invokeInstanceMethod(scope, "iterator")
            val hasNext2 = it2.getInstanceMethod(scope, "hasNext")
            val next2 = it2.getInstanceMethod(scope, "next")
            
            while (it1.hasNext()) {
                if (!hasNext2.invokeCallable(scope, it2).toBool()) return 1 // I'm longer
                val v1 = it1.next()
                val v2 = next2.invokeCallable(scope, it2)
                val d = v1.compareTo(scope, v2)
                if (d != 0) return d
            }
            return if (hasNext2.invokeCallable(scope, it2).toBool()) -1 else 0
        }
        return -2
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        when {
            other is ObjList ->
                ObjList((list + other.list).toMutableList())

            other.isInstanceOf(ObjIterable) && other !is ObjString && other !is ObjBuffer -> {
                val l = other.callMethod<ObjList>(scope, "toList")
                ObjList((list + l.list).toMutableList())
            }

            else -> {
                val newList = list.toMutableList()
                newList.add(other)
                ObjList(newList)
            }
        }


    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        if (other is ObjList) {
            list.addAll(other.list)
        } else if (other.isInstanceOf(ObjIterable) && other !is ObjString && other !is ObjBuffer) {
            val otherList = (other.invokeInstanceMethod(scope, "toList") as ObjList).list
            list.addAll(otherList)
        } else {
            list.add(other)
        }
        return this
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
            // Fast path: int membership in a list of ints (common case in benches)
            if (other is ObjInt) {
                var i = 0
                val sz = list.size
                while (i < sz) {
                    val v = list[i]
                    if (v is ObjInt && v.value == other.value) return true
                    i++
                }
                return false
            }
        }
        return list.contains(other)
    }

    override suspend fun enumerate(scope: Scope, callback: EnumerateCallback) {
        for (item in list) {
            if (!callback.call(item)) break
        }
    }

    override val objClass: ObjClass
        get() = type

    override suspend fun toKotlin(scope: Scope): Any {
        val res = ArrayList<Any?>(list.size)
        for (i in list) res.add(i.toKotlin(scope))
        return res
    }

    suspend fun quicksort(compare: suspend (Obj, Obj) -> Int) = quicksort(compare, 0, list.size - 1)

    suspend fun quicksort(compare: suspend (Obj, Obj) -> Int, left: Int, right: Int) {
        if (left >= right) return
        var i = left
        var j = right
        val pivot = list[left]
        while (i < j) {
            // Сдвигаем j влево, пока элемент меньше pivot
            while (i < j && compare(list[j], pivot) >= 0) {
                j--
            }
            // Сдвигаем i вправо, пока элемент больше pivot
            while (i < j && compare(list[i], pivot) <= 0) {
                i++
            }
            if (i < j) {
                list.swap(i, j)
            }
        }
        // После завершения i == j, ставим pivot на своё место
        list.swap(left, i)
        // Рекурсивно сортируем левую и правую части
        quicksort(compare, left, i - 1)
        quicksort(compare, i + 1, right)
    }

    override fun hashCode(): Int {
        // check?
        return list.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjList

        return list == other.list
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAnyList(scope,list)
    }

    override suspend fun lynonType(): LynonType = LynonType.List

    override suspend fun toJson(scope: Scope): JsonElement {
        val res = ArrayList<JsonElement>(list.size)
        for (i in list) res.add(i.toJson(scope))
        return JsonArray(res)
    }

    override suspend fun defaultToString(scope: Scope): ObjString {
        return ObjString(buildString {
            append("[")
            var first = true
            for (v in list) {
                if (first) first = false else append(",")
                append(v.toString(scope).value)
            }
            append("]")
        })
    }

    companion object {
        val type = object : ObjClass("List", ObjArray) {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjList(decoder.decodeAnyList(scope))
            }
        }.apply {
            addPropertyDoc(
                name = "size",
                doc = "Number of elements in this list.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        return (scp.thisObj as ObjList).list.size.toObj()
                    }
                }
            )
            addFnDoc(
                name = "add",
                doc = "Append one or more elements to the end of this list.",
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val l = scp.thisAs<ObjList>().list
                        for (a in scp.args) l.add(a)
                        return ObjVoid
                    }
                }
            )
            addFnDoc(
                name = "insertAt",
                doc = "Insert elements starting at the given index.",
                params = listOf(ParamDoc("index", type("lyng.Int"))),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        if (scp.args.size < 2) scp.raiseError("addAt takes 2+ arguments")
                        val l = scp.thisAs<ObjList>()
                        var index = scp.requiredArg<ObjInt>(0).value.toInt()
                        for (i in 1..<scp.args.size) l.list.add(index++, scp.args[i])
                        return ObjVoid
                    }
                }
            )

            addFnDoc(
                name = "removeAt",
                doc = "Remove element at index, or a range [start,end) if two indices are provided. Returns the list.",
                params = listOf(ParamDoc("start", type("lyng.Int")), ParamDoc("end", type("lyng.Int"))),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val self = scp.thisAs<ObjList>()
                        val start = scp.requiredArg<ObjInt>(0).value.toInt()
                        if (scp.args.size == 2) {
                            val end = scp.requireOnlyArg<ObjInt>().value.toInt()
                            self.list.subList(start, end).clear()
                        } else
                            self.list.removeAt(start)
                        return self
                    }
                }
            )

            addFnDoc(
                name = "removeLast",
                doc = "Remove the last element or the last N elements if a count is provided. Returns the list.",
                params = listOf(ParamDoc("count", type("lyng.Int"))),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val self = scp.thisAs<ObjList>()
                        if (scp.args.isNotEmpty()) {
                            val count = scp.requireOnlyArg<ObjInt>().value.toInt()
                            val size = self.list.size
                            if (count >= size) self.list.clear()
                            else self.list.subList(size - count, size).clear()
                        } else self.list.removeLast()
                        return self
                    }
                }
            )

            addFnDoc(
                name = "removeRange",
                doc = "Remove a range of elements. Accepts a Range or (start, endInclusive). Returns the list.",
                params = listOf(ParamDoc("range")),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val self = scp.thisAs<ObjList>()
                        val list = self.list
                        val range = scp.requiredArg<Obj>(0)
                        if (range is ObjRange) {
                            val index = range
                            when {
                                index.start is ObjInt && index.end is ObjInt -> {
                                    if (index.isEndInclusive)
                                        list.subList(index.start.toInt(), index.end.toInt() + 1)
                                    else
                                        list.subList(index.start.toInt(), index.end.toInt())
                                }

                                index.isOpenStart && !index.isOpenEnd -> {
                                    if (index.isEndInclusive)
                                        list.subList(0, index.end!!.toInt() + 1)
                                    else
                                        list.subList(0, index.end!!.toInt())
                                }

                                index.isOpenEnd && !index.isOpenStart -> {
                                    list.subList(index.start!!.toInt(), list.size)
                                }

                                index.isOpenStart && index.isOpenEnd -> {
                                    list
                                }

                                else -> {
                                    throw RuntimeException("Can't apply range for index: $index")
                                }
                            }.clear()
                        } else {
                            val start = range.toInt()
                            val end = scp.requiredArg<ObjInt>(1).value.toInt() + 1
                            self.list.subList(start, end).clear()
                        }
                        return self
                    }
                }
            )

            addFnDoc(
                name = "sortWith",
                doc = "Sort this list in-place using a comparator function (a, b) -> Int.",
                params = listOf(ParamDoc("comparator")),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val comparator = scp.requireOnlyArg<Statement>()
                        scp.thisAs<ObjList>().quicksort { a, b -> comparator.invokeCallable(scp, scp.thisObj, a, b).toInt() }
                        return ObjVoid
                    }
                }
            )
            addFnDoc(
                name = "shuffle",
                doc = "Shuffle elements of this list in-place.",
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        scp.thisAs<ObjList>().list.shuffle()
                        return ObjVoid
                    }
                }
            )
            addFnDoc(
                name = "sum",
                doc = "Sum elements using dynamic '+' or optimized integer path. Returns null for empty lists.",
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val self = scp.thisAs<ObjList>()
                        val l = self.list
                        if (l.isEmpty()) return ObjNull
                        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
                            // Fast path: all ints → accumulate as long
                            var i = 0
                            var acc: Long = 0
                            while (i < l.size) {
                                val v = l[i]
                                if (v is ObjInt) {
                                    acc += v.value
                                    i++
                                } else {
                                    // Fallback to generic dynamic '+' accumulation starting from current acc
                                    var res: Obj = ObjInt(acc)
                                    while (i < l.size) {
                                        res = res.plus(scp, l[i])
                                        i++
                                    }
                                    return res
                                }
                            }
                            return ObjInt(acc)
                        }
                        // Generic path: dynamic '+' starting from first element
                        var res: Obj = l[0]
                        var k = 1
                        while (k < l.size) {
                            res = res.plus(scp, l[k])
                            k++
                        }
                        return res
                    }
                }
            )
            addFnDoc(
                name = "min",
                doc = "Minimum element by natural order. Returns null for empty lists.",
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val l = scp.thisAs<ObjList>().list
                        if (l.isEmpty()) return ObjNull
                        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
                            var i = 0
                            var hasOnlyInts = true
                            var minVal: Long = Long.MAX_VALUE
                            while (i < l.size) {
                                val v = l[i]
                                if (v is ObjInt) {
                                    if (v.value < minVal) minVal = v.value
                                } else {
                                    hasOnlyInts = false
                                    break
                                }
                                i++
                            }
                            if (hasOnlyInts) return ObjInt(minVal)
                        }
                        var res: Obj = l[0]
                        var i = 1
                        while (i < l.size) {
                            val v = l[i]
                            if (v.compareTo(scp, res) < 0) res = v
                            i++
                        }
                        return res
                    }
                }
            )
            addFnDoc(
                name = "max",
                doc = "Maximum element by natural order. Returns null for empty lists.",
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val l = scp.thisAs<ObjList>().list
                        if (l.isEmpty()) return ObjNull
                        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS) {
                            var i = 0
                            var hasOnlyInts = true
                            var maxVal: Long = Long.MIN_VALUE
                            while (i < l.size) {
                                val v = l[i]
                                if (v is ObjInt) {
                                    if (v.value > maxVal) maxVal = v.value
                                } else {
                                    hasOnlyInts = false
                                    break
                                }
                                i++
                            }
                            if (hasOnlyInts) return ObjInt(maxVal)
                        }
                        var res: Obj = l[0]
                        var i = 1
                        while (i < l.size) {
                            val v = l[i]
                            if (v.compareTo(scp, res) > 0) res = v
                            i++
                        }
                        return res
                    }
                }
            )
            addFnDoc(
                name = "indexOf",
                doc = "Index of the first occurrence of the given element, or -1 if not found.",
                params = listOf(ParamDoc("element")),
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val l = scp.thisAs<ObjList>().list
                        val needle = scp.args.firstAndOnly()
                        if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS && needle is ObjInt) {
                            var i = 0
                            while (i < l.size) {
                                val v = l[i]
                                if (v is ObjInt && v.value == needle.value) return ObjInt(i.toLong())
                                i++
                            }
                            return ObjInt((-1).toLong())
                        }
                        var i = 0
                        while (i < l.size) {
                            if (l[i].compareTo(scp, needle) == 0) return ObjInt(i.toLong())
                            i++
                        }
                        return ObjInt((-1).toLong())
                    }
                }
            )
        }
    }
}

// Расширение MutableList для удобного обмена элементами
fun <T>MutableList<T>.swap(i: Int, j: Int) {
    if (i in indices && j in indices) {
        val temp = this[i]
        this[i] = this[j]
        this[j] = temp
    }
}


