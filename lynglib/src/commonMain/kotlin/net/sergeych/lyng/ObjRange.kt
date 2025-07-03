package net.sergeych.lyng

class ObjRange(val start: Obj?, val end: Obj?, val isEndInclusive: Boolean) : Obj() {

    val isOpenStart by lazy { start == null || start.isNull }
    val isOpenEnd by lazy { end == null || end.isNull }

    override val objClass: ObjClass = type

    override fun toString(): String {
        val result = StringBuilder()
        result.append("${start?.inspect() ?: '∞'} ..")
        if (!isEndInclusive) result.append('<')
        result.append(" ${end?.inspect() ?: '∞'}")
        return result.toString()
    }

    suspend fun containsRange(scope: Scope, other: ObjRange): Boolean {
        if (start != null) {
            // our start is not -∞ so other start should be GTE or is not contained:
            if (other.start != null && start.compareTo(scope, other.start) > 0) return false
        }
        if (end != null) {
            // same with the end: if it is open, it can't be contained in ours:
            if (other.end == null) return false
            // both exists, now there could be 4 cases:
            return when {
                other.isEndInclusive && isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                !other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                other.isEndInclusive && !isEndInclusive ->
                    end.compareTo(scope, other.end) > 0

                !other.isEndInclusive && isEndInclusive ->
                    end.compareTo(scope, other.end) >= 0

                else -> throw IllegalStateException("unknown comparison")
            }
        }
        return true
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {

        if (other is ObjRange)
            return containsRange(scope, other)

        if (start == null && end == null) return true
        if (start != null) {
            if (start.compareTo(scope, other) > 0) return false
        }
        if (end != null) {
            val cmp = end.compareTo(scope, other)
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

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
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

