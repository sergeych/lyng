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
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

open class ObjList(initialList: MutableList<Obj> = mutableListOf()) : Obj() {
    internal var boxedList: MutableList<Obj>? = null
    internal var primitiveIntList: LongArray? = null
    // Logical size of primitiveIntList; capacity = primitiveIntList!!.size
    internal var primitiveIntSize: Int = 0

    init {
        if (initialList.isNotEmpty()) {
            if (!adoptPrimitiveIntList(initialList)) {
                boxedList = initialList
            }
        }
        // Empty initialList: both null — lazy mode, avoids boxing on first append
    }

    val list: MutableList<Obj>
        get() = ensureBoxedList()

    internal fun sizeFast(): Int = when {
        primitiveIntList != null -> primitiveIntSize
        else -> boxedList?.size ?: 0
    }

    internal fun getObjAtFast(index: Int): Obj =
        primitiveIntList?.let { ObjInt.of(it[index]) } ?: boxedList!![index]

    internal fun getIntAtFast(index: Int): Long? =
        primitiveIntList?.get(index) ?: (boxedList?.get(index) as? ObjInt)?.value

    internal fun setObjAtFast(index: Int, value: Obj) {
        val ints = primitiveIntList
        if (ints != null) {
            if (value is ObjInt) {
                ints[index] = value.value
                return
            }
            ensureBoxedList()[index] = value
            return
        }
        boxedList!![index] = value
    }

    internal fun setIntAtFast(index: Int, value: Long) {
        val ints = primitiveIntList
        if (ints != null) {
            ints[index] = value
            return
        }
        boxedList?.let {
            if (it[index] is ObjInt) {
                it[index] = ObjInt.of(value)
                return
            }
        }
        ensureBoxedList()[index] = ObjInt.of(value)
    }

    internal fun appendFast(value: Obj) {
        val ints = primitiveIntList
        if (value is ObjInt) {
            if (ints != null) {
                // Primitive mode: amortized growth (no copy when capacity allows)
                if (primitiveIntSize < ints.size) {
                    ints[primitiveIntSize++] = value.value
                } else {
                    val grown = ints.copyOf(maxOf(ints.size * 2, 16))
                    grown[primitiveIntSize++] = value.value
                    primitiveIntList = grown
                }
                return
            }
            if (boxedList == null) {
                // Lazy empty state: first int element starts primitive mode
                primitiveIntList = LongArray(16).also { it[0] = value.value }
                primitiveIntSize = 1
                return
            }
        }
        ensureBoxedList().add(value)
    }

    internal fun appendAllFast(other: ObjList) {
        val ints = primitiveIntList
        val otherInts = other.primitiveIntList
        if (ints != null && otherInts != null) {
            val otherSize = other.primitiveIntSize
            val newSize = primitiveIntSize + otherSize
            val dest = if (newSize <= ints.size) ints else ints.copyOf(newSize)
            otherInts.copyInto(dest, primitiveIntSize, 0, otherSize)
            primitiveIntList = dest
            primitiveIntSize = newSize
            return
        }
        ensureBoxedList().addAll(other.list)
    }

    private fun adoptPrimitiveIntList(items: List<Obj>): Boolean {
        if (items.isEmpty()) return false
        val ints = LongArray(items.size)
        for (i in items.indices) {
            val value = items[i] as? ObjInt ?: return false
            ints[i] = value.value
        }
        primitiveIntList = ints
        primitiveIntSize = ints.size
        boxedList = null
        return true
    }

    private fun ensureBoxedList(): MutableList<Obj> {
        boxedList?.let { return it }
        val ints = primitiveIntList
        if (ints == null) {
            val empty = mutableListOf<Obj>()
            boxedList = empty
            return empty
        }
        val materialized = ArrayList<Obj>(primitiveIntSize)
        for (i in 0..<primitiveIntSize) {
            materialized.add(ObjInt.of(ints[i]))
        }
        boxedList = materialized
        primitiveIntList = null
        primitiveIntSize = 0
        return materialized
    }

    private fun sliceRange(start: Int, endExclusive: Int): Obj {
        val ints = primitiveIntList
        return if (ints != null) {
            ObjList(ints.copyOfRange(start, endExclusive))
        } else {
            ObjList(list.subList(start, endExclusive).toMutableList())
        }
    }

    internal constructor(intValues: LongArray, size: Int = intValues.size) : this(mutableListOf()) {
        primitiveIntList = intValues
        primitiveIntSize = size
        boxedList = null
    }

    protected open fun shouldTreatAsSingleElement(scope: Scope, other: Obj): Boolean {
        if (!other.isInstanceOf(ObjIterable)) return true
        val declaredElementType = scope.declaredListElementTypeForValue(this)
        if (declaredElementType != null && matchesTypeDecl(scope, other, declaredElementType)) {
            return true
        }
        if (other is ObjString || other is ObjBuffer) return true
        return false
    }

    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (this === other) return true
        if (other !is ObjList) {
            if (other.isInstanceOf(ObjIterable)) {
                return compareTo(scope, other) == 0
            }
            return false
        }
        if (sizeFast() != other.sizeFast()) return false
        for (i in 0..<sizeFast()) {
            if (!getObjAtFast(i).equals(scope, other.getObjAtFast(i))) return false
        }
        return true
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return when (index) {
            is ObjInt -> {
                val i = index.toInt()
                objListBoundsViolationMessageOrNull(sizeFast(), i)?.let { scope.raiseIndexOutOfBounds(it) }
                getObjAtFast(i)
            }

            is ObjRange -> {
                when {
                    index.start is ObjInt && index.end is ObjInt -> {
                        if (index.isEndInclusive)
                            sliceRange(index.start.toInt(), index.end.toInt() + 1)
                        else
                            sliceRange(index.start.toInt(), index.end.toInt())
                    }

                    index.isOpenStart && !index.isOpenEnd -> {
                        if (index.isEndInclusive)
                            sliceRange(0, index.end!!.toInt() + 1)
                        else
                            sliceRange(0, index.end!!.toInt())
                    }

                    index.isOpenEnd && !index.isOpenStart -> {
                        sliceRange(index.start!!.toInt(), sizeFast())
                    }

                    index.isOpenStart && index.isOpenEnd -> {
                        sliceRange(0, sizeFast())
                    }

                    else -> {
                        throw RuntimeException("Can't apply range for index: $index")
                    }
                }
            }

            else -> scope.raiseIllegalArgument("Illegal index object for a list: ${index.inspect(scope)}")
        }
    }

    open override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        val i = index.toInt()
        objListBoundsViolationMessageOrNull(sizeFast(), i)?.let { scope.raiseIndexOutOfBounds(it) }
        setObjAtFast(i, newValue)
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other is ObjList) {
            val mySize = sizeFast()
            val otherSize = other.sizeFast()
            val commonSize = minOf(mySize, otherSize)
            for (i in 0..<commonSize) {
                val d = getObjAtFast(i).compareTo(scope, other.getObjAtFast(i))
                if (d != 0) {
                    return d
                }
            }
            val res = mySize.compareTo(otherSize)
            return res
        }
        if (other.isInstanceOf(ObjIterable)) {
            val it2 = other.invokeInstanceMethod(scope, "iterator")
            val hasNext2 = it2.getInstanceMethod(scope, "hasNext")
            val next2 = it2.getInstanceMethod(scope, "next")

            for (i in 0..<sizeFast()) {
                if (!hasNext2.invoke(scope, it2).toBool()) return 1 // I'm longer
                val v1 = getObjAtFast(i)
                val v2 = next2.invoke(scope, it2)
                val d = v1.compareTo(scope, v2)
                if (d != 0) return d
            }
            return if (hasNext2.invoke(scope, it2).toBool()) -1 else 0
        }
        return -2
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj =
        when {
            other is ObjList -> {
                val ints = primitiveIntList
                val otherInts = other.primitiveIntList
                if (ints != null && otherInts != null) {
                    val mySize = primitiveIntSize
                    val otherSize = other.primitiveIntSize
                    ObjList(LongArray(mySize + otherSize).also {
                        ints.copyInto(it, 0, 0, mySize)
                        otherInts.copyInto(it, mySize, 0, otherSize)
                    })
                } else {
                    ObjList((list + other.list).toMutableList())
                }
            }

            !shouldTreatAsSingleElement(scope, other) && other.isInstanceOf(ObjIterable) -> {
                val l = other.callMethod<ObjList>(scope, "toList")
                ObjList((list + l.list).toMutableList())
            }

            else -> {
                val newList = list.toMutableList()
                newList.add(other)
                ObjList(newList)
            }
        }


    open override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        if (other is ObjInt || other is ObjString || other is ObjBool || other is ObjReal || other is ObjNull) {
            appendFast(other)
        } else if (other is ObjList) {
            appendAllFast(other)
        } else if (!shouldTreatAsSingleElement(scope, other) && other.isInstanceOf(ObjIterable)) {
            val otherList = (other.invokeInstanceMethod(scope, "toList") as ObjList).list
            list.addAll(otherList)
        } else {
            appendFast(other)
        }
        return this
    }

    override suspend fun minus(scope: Scope, other: Obj): Obj {
        val out = list.toMutableList()
        if (shouldTreatAsSingleElement(scope, other)) {
            out.remove(other)
            return ObjList(out)
        }
        if (other.isInstanceOf(ObjIterable)) {
            val toRemove = mutableSetOf<Obj>()
            other.enumerate(scope) {
                toRemove += it
                true
            }
            out.removeAll { toRemove.contains(it) }
            return ObjList(out)
        }
        out.remove(other)
        return ObjList(out)
    }

    open override suspend fun minusAssign(scope: Scope, other: Obj): Obj {
        if (shouldTreatAsSingleElement(scope, other)) {
            list.remove(other)
            return this
        }
        if (other.isInstanceOf(ObjIterable)) {
            val toRemove = mutableSetOf<Obj>()
            other.enumerate(scope) {
                toRemove += it
                true
            }
            list.removeAll { toRemove.contains(it) }
            return this
        }
        list.remove(other)
        return this
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        val ints = primitiveIntList
        if (ints != null && other is ObjInt) {
            for (i in 0..<primitiveIntSize) {
                if (ints[i] == other.value) return true
            }
            return false
        }
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

    override suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        val ints = primitiveIntList
        if (ints != null) {
            for (i in 0..<primitiveIntSize) {
                if (!callback(ObjInt.of(ints[i]))) break
            }
            return
        }
        for (item in list) {
            if (!callback(item)) break
        }
    }

    open override val objClass: ObjClass
        get() = type

    override suspend fun toKotlin(scope: Scope): Any {
        val ints = primitiveIntList
        if (ints != null) return (0..<primitiveIntSize).map { ints[it] }
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
        val ints = primitiveIntList
        return if (ints != null) {
            var result = 1
            for (i in 0..<primitiveIntSize) result = 31 * result + ints[i].hashCode()
            result
        } else list.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ObjList
        val ints = primitiveIntList
        val otherInts = other.primitiveIntList
        return if (ints != null && otherInts != null) {
            if (primitiveIntSize != other.primitiveIntSize) return false
            for (i in 0..<primitiveIntSize) if (ints[i] != otherInts[i]) return false
            true
        } else {
            list == other.list
        }
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        val ints = primitiveIntList
        if (ints != null) {
            val boxed = ArrayList<Obj>(primitiveIntSize)
            for (i in 0..<primitiveIntSize) boxed.add(ObjInt.of(ints[i]))
            encoder.encodeAnyList(scope, boxed)
            return
        }
        encoder.encodeAnyList(scope, list)
    }

    override suspend fun lynonType(): LynonType = LynonType.List

    override suspend fun toJson(scope: Scope): JsonElement {
        val ints = primitiveIntList
        if (ints != null) {
            return JsonArray((0..<primitiveIntSize).map { ObjInt.of(ints[it]).toJson(scope) })
        }
        return JsonArray(list.map { it.toJson(scope) })
    }

    override suspend fun defaultToString(scope: Scope): ObjString {
        return ObjString(buildString {
            append("[")
            var first = true
            val ints = primitiveIntList
            if (ints != null) {
                for (i in 0..<primitiveIntSize) {
                    if (first) first = false else append(",")
                    append(ints[i])
                }
            } else {
                for (v in list) {
                    if (first) first = false else append(",")
                    append(v.toString(scope).value)
                }
            }
            append("]")
        })
    }

    companion object {
        val type = object : ObjClass("List", ObjArray) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjList(scope.args.list.toMutableList())
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjList(decoder.decodeAnyList(scope))
            }
        }.apply {
            addPropertyDoc(
                name = "size",
                doc = "Number of elements in this list.",
                type = type("lyng.Int"),
                moduleName = "lyng.stdlib",
                getter = { 
                    val s = (this.thisObj as ObjList).sizeFast()
                    s.toObj()
                }
            )
            addFnDoc(
                name = "add",
                doc = "Append one or more elements to the end of this list.",
                moduleName = "lyng.stdlib"
            ) {
                val l = thisAs<ObjList>()
                for (a in args) l.appendFast(a)
                ObjVoid
            }
            addFnDoc(
                name = "insertAt",
                doc = "Insert elements starting at the given index.",
                params = listOf(ParamDoc("index", type("lyng.Int"))),
                moduleName = "lyng.stdlib"
            ) {
                if (args.size < 2) raiseError("addAt takes 2+ arguments")
                val l = thisAs<ObjList>()
                var index = requiredArg<ObjInt>(0).value.toInt()
                for (i in 1..<args.size) l.list.add(index++, args[i])
                ObjVoid
            }

            addFnDoc(
                name = "removeAt",
                doc = "Remove element at index, or a range [start,end) if two indices are provided. Returns the list.",
                params = listOf(ParamDoc("start", type("lyng.Int")), ParamDoc("end", type("lyng.Int"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjList>()
                val start = requiredArg<ObjInt>(0).value.toInt()
                if (args.size == 2) {
                    val end = requireOnlyArg<ObjInt>().value.toInt()
                    self.list.subList(start, end).clear()
                } else
                    self.list.removeAt(start)
                self
            }

            addFnDoc(
                name = "removeLast",
                doc = "Remove the last element or the last N elements if a count is provided. Returns the list.",
                params = listOf(ParamDoc("count", type("lyng.Int"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjList>()
                if (args.isNotEmpty()) {
                    val count = requireOnlyArg<ObjInt>().value.toInt()
                    val size = self.list.size
                    if (count >= size) self.list.clear()
                    else self.list.subList(size - count, size).clear()
                } else self.list.removeLast()
                self
            }
            addFnDoc(
                name= "ensureCapacity",
                doc = """
                    ensure the list capacity allows storing specified amount if items without reallocation.
                    If current capacity is greater or equal to `count`, does nothing. Note that possible reallocation
                    could be a costly operation,
                """.trimIndent(),
                params = listOf(ParamDoc("count", type("lyng.Int"))),
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjList>()
                val count = requireOnlyArg<ObjInt>().value.toInt()
                if (count > 0) {
                    val ints = self.primitiveIntList
                    when {
                        ints != null -> if (ints.size < count) self.primitiveIntList = ints.copyOf(count)
                        self.boxedList == null -> { self.primitiveIntList = LongArray(count); self.primitiveIntSize = 0 }
                        else -> (self.boxedList as? ArrayList)?.ensureCapacity(count)
                    }
                }
                self
            }

            addFnDoc(
                name = "removeRange",
                doc = "Remove a range of elements. Accepts a Range or (start, endInclusive). Returns the list.",
                params = listOf(ParamDoc("range")),
                moduleName = "lyng.stdlib"
            ) {
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

            addFnDoc(
                name = "sortWith",
                doc = "Sort this list in-place using a comparator function (a, b) -> Int.",
                params = listOf(ParamDoc("comparator")),
                moduleName = "lyng.stdlib"
            ) {
                val comparator = requireOnlyArg<Obj>()
                thisAs<ObjList>().quicksort { a, b ->
                    call(comparator, Arguments(a, b)).toInt()
                }
                ObjVoid
            }
            addFnDoc(
                name = "shuffle",
                doc = "Shuffle elements of this list in-place.",
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjList>().list.shuffle()
                ObjVoid
            }
            addFnDoc(
                name = "sum",
                doc = "Sum elements using dynamic '+' or optimized integer path. Returns null for empty lists.",
                moduleName = "lyng.stdlib"
            ) {
                val self = thisAs<ObjList>()
                val l = self.list
                if (l.isEmpty()) return@addFnDoc ObjNull
                val scope = requireScope()
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
                                res = res.plus(scope, l[i])
                                i++
                            }
                            return@addFnDoc res
                        }
                    }
                    return@addFnDoc ObjInt(acc)
                }
                // Generic path: dynamic '+' starting from first element
                var res: Obj = l[0]
                var k = 1
                while (k < l.size) {
                    res = res.plus(scope, l[k])
                    k++
                }
                res
            }
            addFnDoc(
                name = "min",
                doc = "Minimum element by natural order. Returns null for empty lists.",
                moduleName = "lyng.stdlib"
            ) {
                val l = thisAs<ObjList>().list
                if (l.isEmpty()) return@addFnDoc ObjNull
                val scope = requireScope()
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
                    if (hasOnlyInts) return@addFnDoc ObjInt(minVal)
                }
                var res: Obj = l[0]
                var i = 1
                while (i < l.size) {
                    val v = l[i]
                    if (v.compareTo(scope, res) < 0) res = v
                    i++
                }
                res
            }
            addFnDoc(
                name = "max",
                doc = "Maximum element by natural order. Returns null for empty lists.",
                moduleName = "lyng.stdlib"
            ) {
                val l = thisAs<ObjList>().list
                if (l.isEmpty()) return@addFnDoc ObjNull
                val scope = requireScope()
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
                    if (hasOnlyInts) return@addFnDoc ObjInt(maxVal)
                }
                var res: Obj = l[0]
                var i = 1
                while (i < l.size) {
                    val v = l[i]
                    if (v.compareTo(scope, res) > 0) res = v
                    i++
                }
                res
            }
            addFnDoc(
                name = "indexOf",
                doc = "Index of the first occurrence of the given element, or -1 if not found.",
                params = listOf(ParamDoc("element")),
                returns = type("lyng.Int"),
                moduleName = "lyng.stdlib"
            ) {
                val l = thisAs<ObjList>().list
                val needle = args.firstAndOnly()
                val scope = requireScope()
                if (net.sergeych.lyng.PerfFlags.PRIMITIVE_FASTOPS && needle is ObjInt) {
                    var i = 0
                    while (i < l.size) {
                        val v = l[i]
                        if (v is ObjInt && v.value == needle.value) return@addFnDoc ObjInt(i.toLong())
                        i++
                    }
                    return@addFnDoc ObjInt((-1).toLong())
                }
                var i = 0
                while (i < l.size) {
                    if (l[i].compareTo(scope, needle) == 0) return@addFnDoc ObjInt(i.toLong())
                    i++
                }
                ObjInt((-1).toLong())
            }
            addFnDoc(
                name = "toImmutable",
                doc = "Create an immutable snapshot of this list.",
                returns = type("lyng.ImmutableList"),
                moduleName = "lyng.stdlib"
            ) {
                ObjImmutableList(thisAs<ObjList>().list)
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
