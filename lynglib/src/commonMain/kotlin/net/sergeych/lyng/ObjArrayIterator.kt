package net.sergeych.lyng

class ObjArrayIterator(val array: Obj) : Obj() {

    override val objClass: ObjClass by lazy { type }

    private var nextIndex = 0
    private var lastIndex = 0

    suspend fun init(scope: Scope) {
        nextIndex = 0
        lastIndex = array.invokeInstanceMethod(scope, "size").toInt()
        ObjVoid
    }

    companion object {
        val type by lazy {
            ObjClass("ArrayIterator", ObjIterator).apply {
                addFn("next") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex < self.lastIndex) {
                        self.array.invokeInstanceMethod(this, "getAt", (self.nextIndex++).toObj())
                    } else raiseError(ObjIterationFinishedException(this))
                }
                addFn("hasNext") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex < self.lastIndex) ObjTrue else ObjFalse
                }
            }
        }
    }
}