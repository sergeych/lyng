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

    val isIntRange: Boolean by lazy {
        start is ObjInt && end is ObjInt
    }

    val isCharRange: Boolean by lazy {
        start is ObjChar && end is ObjChar
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        return (other as? ObjRange)?.let {
            if( start == other.start && end == other.end ) 0 else -1
        }
            ?: -1
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

