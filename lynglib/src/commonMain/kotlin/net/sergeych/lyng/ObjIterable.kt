package net.sergeych.lyng

/**
 * Abstract class that must provide `iterator` method that returns [ObjIterator] instance.
 */
val ObjIterable by lazy {
    ObjClass("Iterable").apply {

        addFn("toList") {
            val result = mutableListOf<Obj>()
            val iterator = thisObj.invokeInstanceMethod(this, "iterator")

            while (iterator.invokeInstanceMethod(this, "hasNext").toBool())
                result += iterator.invokeInstanceMethod(this, "next")
            ObjList(result)
        }

        // it is not effective, but it is open:
        addFn("contains", isOpen = true) {
            val obj = args.firstAndOnly()
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.compareTo(this, it.invokeInstanceMethod(this, "next")) == 0)
                    return@addFn ObjTrue
            }
            ObjFalse
        }

        addFn("indexOf", isOpen = true) {
            val obj = args.firstAndOnly()
            var index = 0
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                if (obj.compareTo(this, it.invokeInstanceMethod(this, "next")) == 0)
                    return@addFn ObjInt(index.toLong())
                index++
            }
            ObjInt(-1L)
        }

        addFn("toSet") {
            val result = mutableSetOf<Obj>()
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                result += it.invokeInstanceMethod(this, "next")
            }
            ObjSet(result)
        }

        addFn("forEach", isOpen = true) {
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            val fn = requiredArg<Statement>(0)
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                val x = it.invokeInstanceMethod(this, "next")
                fn.execute(this.copy(Arguments(listOf(x))))
            }
            ObjVoid
        }

        addFn("map", isOpen = true) {
            val it = thisObj.invokeInstanceMethod(this, "iterator")
            val fn = requiredArg<Statement>(0)
            val result = mutableListOf<Obj>()
            while (it.invokeInstanceMethod(this, "hasNext").toBool()) {
                val x = it.invokeInstanceMethod(this, "next")
                result += fn.execute(this.copy(Arguments(listOf(x))))
            }
            ObjList(result)
        }

        addFn("isEmpty") {
            ObjBool(
                thisObj.invokeInstanceMethod(this, "iterator")
                    .invokeInstanceMethod(this, "hasNext").toBool()
                    .not()
            )
        }

    }
}