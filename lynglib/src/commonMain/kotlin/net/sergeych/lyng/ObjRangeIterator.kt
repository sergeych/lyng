package net.sergeych.lyng

class ObjRangeIterator(val self: ObjRange) : Obj() {

    private var nextIndex = 0
    private var lastIndex = 0
    private var isCharRange: Boolean = false

    override val objClass: ObjClass = type

    fun Scope.init() {
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

    fun next(scope: Scope): Obj =
        if (nextIndex < lastIndex) {
            val x = if (self.isEndInclusive)
                self.start!!.toLong() + nextIndex++
            else
                self.start!!.toLong() + nextIndex++
            if( isCharRange ) ObjChar(x.toInt().toChar()) else ObjInt(x)
        }
        else {
            scope.raiseError(ObjIterationFinishedException(scope))
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