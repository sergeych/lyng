package net.sergeych.ling

class ObjRange(val start: Obj?, val end: Obj?, val isEndInclusive: Boolean) : Obj() {

    override val objClass: ObjClass = type

    override fun toString(): String {
        val result = StringBuilder()
        result.append("${start?.inspect() ?: '∞'} ..")
        if (!isEndInclusive) result.append('<')
        result.append(" ${end?.inspect() ?: '∞'}")
        return result.toString()
    }

    suspend fun containsRange(context: Context, other: ObjRange): Boolean {
        if (start != null) {
            // our start is not -∞ so other start should be GTE or is not contained:
            if (other.start != null && start.compareTo(context, other.start) > 0) return false
        }
        if (end != null) {
            // same with the end: if it is open, it can't be contained in ours:
            if (other.end == null) return false
            // both exists, now there could be 4 cases:
            return when {
                other.isEndInclusive && isEndInclusive ->
                    end.compareTo(context, other.end) >= 0

                !other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(context, other.end) >= 0

                other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(context, other.end) > 0

                !other.isEndInclusive && isEndInclusive ->
                    end.compareTo(context, other.end) >= 0

                else -> throw IllegalStateException("unknown comparison")
            }
        }
        return true
    }

    override suspend fun contains(context: Context, other: Obj): Boolean {

        if (other is ObjRange)
            return containsRange(context, other)

        if (start == null && end == null) return true
        if (start != null) {
            if (start.compareTo(context, other) > 0) return false
        }
        if (end != null) {
            val cmp = end.compareTo(context, other)
            if (isEndInclusive && cmp < 0 || !isEndInclusive && cmp <= 0) return false
        }
        return true
    }

    override suspend fun getAt(context: Context, index: Int): Obj {
        if (!isIntRange && !isCharRange) {
            return when (index) {
                0 -> start ?: ObjNull
                1 -> end ?: ObjNull
                else -> context.raiseIndexOutOfBounds("index out of range: $index for max of 2 for non-int ranges")
            }
        }
        // int range, should be finite
        val r0 = start?.toInt() ?: context.raiseArgumentError("start is not integer")
        var r1 = end?.toInt() ?: context.raiseArgumentError("end is not integer")
        if (isEndInclusive) r1++
        val i = index + r0
        if (i >= r1) context.raiseIndexOutOfBounds("index $index is not in range (${r1 - r0})")
        return if (isIntRange) ObjInt(i.toLong()) else ObjChar(i.toChar())
    }


    val isIntRange: Boolean by lazy {
        start is ObjInt && end is ObjInt
    }

    val isCharRange: Boolean by lazy {
        start is ObjChar && end is ObjChar
    }

    companion object {
        val type = ObjClass("Range", ObjIterable).apply {
            addFn("start") {
                thisAs<ObjRange>().start ?: ObjNull
            }
            addFn("end") {
                thisAs<ObjRange>().end ?: ObjNull
            }
            addFn("isOpen") {
                thisAs<ObjRange>().let { it.start == null || it.end == null }.toObj()
            }
            addFn("isIntRange") {
                thisAs<ObjRange>().isIntRange.toObj()
            }
            addFn("isCharRange") {
                thisAs<ObjRange>().isCharRange.toObj()
            }
            addFn("isEndInclusive") {
                thisAs<ObjRange>().isEndInclusive.toObj()
            }
            addFn("iterator") {
                val self = thisAs<ObjRange>()
                ObjRangeIterator(self).apply { init() }
            }
        }
    }
}

class ObjRangeIterator(val self: ObjRange) : Obj() {

    private var nextIndex = 0
    private var lastIndex = 0
    private var isCharRange: Boolean = false

    override val objClass: ObjClass = type

    fun Context.init() {
        if (self.start == null || self.end == null)
            raiseError("next is only available for finite ranges")
        isCharRange = self.isCharRange
        lastIndex = if (self.isIntRange || self.isCharRange) {
            if (self.isEndInclusive)
                self.end.toInt() - self.start.toInt() + 1
            else
                self.end.toInt() - self.start.toInt()
        } else {
            raiseError("not implemented iterator for range of $this")
        }
    }

    fun hasNext(): Boolean = nextIndex < lastIndex

    fun next(context: Context): Obj =
        if (nextIndex < lastIndex) {
            val x = if (self.isEndInclusive)
                self.start!!.toLong() + nextIndex++
            else
                self.start!!.toLong() + nextIndex++
            if( isCharRange ) ObjChar(x.toInt().toChar()) else ObjInt(x)
        }
        else {
            context.raiseError(ObjIterationFinishedError(context))
        }

    companion object {
        val type = ObjClass("RangeIterator", ObjIterable).apply {
            addFn("hasNext") {
                thisAs<ObjRangeIterator>().hasNext().toObj()
            }
            addFn("next") {
                thisAs<ObjRangeIterator>().next(this)
            }
        }
    }
}