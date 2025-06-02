package net.sergeych.lying

import kotlin.math.*

class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
) : Statement() {

    override suspend fun execute(context: Context): Obj {
        var lastResult: Obj = ObjVoid
        for (s in statements) {
            lastResult = s.execute(context)
        }
        return lastResult
    }

    suspend fun execute() = execute(defaultContext.copy(pos))

    companion object {
        val defaultContext: Context = Context().apply {
            addFn("println") {
                print("yn: ")
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + a.asStr.value)
                    else print(a.asStr.value)
                }
                println()
                ObjVoid
            }
            addFn("floor") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(floor(x.toDouble())))
            }
            addFn("ceil") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(ceil(x.toDouble())))
            }
            addFn("round") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(round(x.toDouble())))
            }
            addFn("sin") {
                ObjReal(sin(args.firstAndOnly().toDouble()))
            }

            addVoidFn("assert") {
                val cond = requiredArg<ObjBool>(0)
                if( !cond.value == true )
                    raiseError(ObjAssertionError(this,"Assertion failed"))
            }

            addConst("Real", ObjReal.type)
            addConst("String", ObjString.type)
            addConst("Int", ObjInt.type)
            addConst("Bool", ObjBool.type)
            addConst("Char", ObjChar.type)
            addConst("List", ObjList.type)
            addConst("Range", ObjRange.type)

            // interfaces
            addConst("Iterable", ObjIterable)
            addConst("Array", ObjArray)

            val pi = ObjReal(PI)
            addConst("π", pi)
            getOrCreateNamespace("Math").apply {
                addConst("PI", pi)
            }
        }
    }
}