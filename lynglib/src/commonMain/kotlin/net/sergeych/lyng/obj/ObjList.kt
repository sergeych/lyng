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
import net.sergeych.lyng.Statement
import net.sergeych.lyng.statement
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

class ObjList(val list: MutableList<Obj> = mutableListOf()) : Obj() {

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
        if (other !is ObjList) return -2
        val mySize = list.size
        val otherSize = other.list.size
        val commonSize = minOf(mySize, otherSize)
        for (i in 0..<commonSize) {
            if (list[i].compareTo(scope, other.list[i]) != 0) {
                return list[i].compareTo(scope, other.list[i])
            }
        }
        // equal so far, longer is greater:
        return when {
            mySize < otherSize -> -1
            mySize > otherSize -> 1
            else -> 0
        }
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        when {
            other is ObjList ->
                ObjList((list + other.list).toMutableList())

            other.isInstanceOf(ObjIterable) -> {
                val l = other.callMethod<ObjList>(scope, "toList")
                ObjList((list + l.list).toMutableList())
            }

            else ->
                scope.raiseError("'+': can't concatenate $this with $other")
        }


    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        // optimization
        if (other is ObjList) {
            list += other.list
            return this
        }
        if (other.isInstanceOf(ObjIterable)) {
            val otherList = other.invokeInstanceMethod(scope, "toList") as ObjList
            list += otherList.list
        } else
            list += other
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

    override val objClass: ObjClass
        get() = type

    override suspend fun toKotlin(scope: Scope): Any {
        return list.map { it.toKotlin(scope) }
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

    companion object {
        val type = object : ObjClass("List", ObjArray) {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjList(decoder.decodeAnyList(scope))
            }
        }.apply {
            createField("size",
                statement {
                    (thisObj as ObjList).list.size.toObj()
                }
            )
            createField("add",
                statement {
                    val l = thisAs<ObjList>().list
                    for (a in args) l.add(a)
                    ObjVoid
                }
            )
            addFn("insertAt") {
                if (args.size < 2) raiseError("addAt takes 2+ arguments")
                val l = thisAs<ObjList>()
                var index = requiredArg<ObjInt>(0).value.toInt()
                for (i in 1..<args.size) l.list.add(index++, args[i])
                ObjVoid
            }

            addFn("removeAt") {
                val self = thisAs<ObjList>()
                val start = requiredArg<ObjInt>(0).value.toInt()
                if (args.size == 2) {
                    val end = requireOnlyArg<ObjInt>().value.toInt()
                    self.list.subList(start, end).clear()
                } else
                    self.list.removeAt(start)
                self
            }

            addFn("removeLast") {
                val self = thisAs<ObjList>()
                if (args.isNotEmpty()) {
                    val count = requireOnlyArg<ObjInt>().value.toInt()
                    val size = self.list.size
                    if (count >= size) self.list.clear()
                    else self.list.subList(size - count, size).clear()
                } else self.list.removeLast()
                self
            }

            addFn("removeRange") {
                val self = thisAs<ObjList>()
                val list = self.list
                val range = requiredArg<Obj>(0)
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
                    val end = requiredArg<ObjInt>(1).value.toInt() + 1
                    self.list.subList(start, end).clear()
                }
                self
            }

            addFn("sortWith") {
                val comparator = requireOnlyArg<Statement>()
                thisAs<ObjList>().quicksort { a, b -> comparator.call(this, a, b).toInt() }
                ObjVoid
            }
            addFn("shuffle") {
                thisAs<ObjList>().list.shuffle()
                ObjVoid
            }
            addFn("sum") {
                val self = thisAs<ObjList>()
                val l = self.list
                if (l.isEmpty()) return@addFn ObjNull
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
                                res = res.plus(this, l[i])
                                i++
                            }
                            return@addFn res
                        }
                    }
                    return@addFn ObjInt(acc)
                }
                // Generic path: dynamic '+' starting from first element
                var res: Obj = l[0]
                var k = 1
                while (k < l.size) {
                    res = res.plus(this, l[k])
                    k++
                }
                res
            }
            addFn("min") {
                val l = thisAs<ObjList>().list
                if (l.isEmpty()) return@addFn ObjNull
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
                    if (hasOnlyInts) return@addFn ObjInt(minVal)
                }
                var res: Obj = l[0]
                var i = 1
                while (i < l.size) {
                    val v = l[i]
                    if (v.compareTo(this, res) < 0) res = v
                    i++
                }
                res
            }
            addFn("max") {
                val l = thisAs<ObjList>().list
                if (l.isEmpty()) return@addFn ObjNull
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
                    if (hasOnlyInts) return@addFn ObjInt(maxVal)
                }
                var res: Obj = l[0]
                var i = 1
                while (i < l.size) {
                    val v = l[i]
                    if (v.compareTo(this, res) > 0) res = v
                    i++
                }
                res
            }
            addFn("indexOf") {
                val l = thisAs<ObjList>().list
                val needle = args.firstAndOnly()
                if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS && needle is ObjInt) {
                    var i = 0
                    while (i < l.size) {
                        val v = l[i]
                        if (v is ObjInt && v.value == needle.value) return@addFn ObjInt(i.toLong())
                        i++
                    }
                    return@addFn ObjInt((-1).toLong())
                }
                var i = 0
                while (i < l.size) {
                    if (l[i].compareTo(this, needle) == 0) return@addFn ObjInt(i.toLong())
                    i++
                }
                ObjInt((-1).toLong())
            }
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


