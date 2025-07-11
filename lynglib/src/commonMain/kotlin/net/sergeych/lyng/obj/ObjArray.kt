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

    }
}