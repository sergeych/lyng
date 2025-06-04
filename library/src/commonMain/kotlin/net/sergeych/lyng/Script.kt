package net.sergeych.lyng

import kotlinx.coroutines.delay
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
            addFn("cos") {
                ObjReal(cos(args.firstAndOnly().toDouble()))
            }
            addFn("tan") {
                ObjReal(tan(args.firstAndOnly().toDouble()))
            }
            addFn("asin") {
                ObjReal(asin(args.firstAndOnly().toDouble()))
            }
            addFn("acos") {
                ObjReal(acos(args.firstAndOnly().toDouble()))
            }
            addFn("atan") {
                ObjReal(atan(args.firstAndOnly().toDouble()))
            }

            addFn("sinh") {
                ObjReal(sinh(args.firstAndOnly().toDouble()))
            }
            addFn("cosh") {
                ObjReal(cosh(args.firstAndOnly().toDouble()))
            }
            addFn("tanh") {
                ObjReal(tanh(args.firstAndOnly().toDouble()))
            }
            addFn("asinh") {
                ObjReal(asinh(args.firstAndOnly().toDouble()))
            }
            addFn("acosh") {
                ObjReal(acosh(args.firstAndOnly().toDouble()))
            }
            addFn("atanh") {
                ObjReal(atanh(args.firstAndOnly().toDouble()))
            }

            addFn("exp") {
                ObjReal(exp(args.firstAndOnly().toDouble()))
            }
            addFn("ln") {
                ObjReal(ln(args.firstAndOnly().toDouble()))
            }

            addFn("log10") {
                ObjReal(log10(args.firstAndOnly().toDouble()))
            }

            addFn("log2") {
                ObjReal(log2(args.firstAndOnly().toDouble()))
            }

            addFn("pow") {
                requireExactCount(2)
                ObjReal(
                    (args[0].toDouble()).pow(args[1].toDouble())
                )
            }
            addFn("sqrt") {
                ObjReal(
                    sqrt(args.firstAndOnly().toDouble())
                )
            }
            addFn( "abs" ) {
                val x = args.firstAndOnly()
                if( x is ObjInt ) ObjInt( x.value.absoluteValue ) else ObjReal( x.toDouble().absoluteValue )
            }

            addVoidFn("assert") {
                val cond = requiredArg<ObjBool>(0)
                if( !cond.value == true )
                    raiseError(ObjAssertionError(this,"Assertion failed"))
            }

            addVoidFn("delay") {
                delay((this.args.firstAndOnly().toDouble()/1000.0).roundToLong())
            }

            addConst("Real", ObjReal.type)
            addConst("String", ObjString.type)
            addConst("Int", ObjInt.type)
            addConst("Bool", ObjBool.type)
            addConst("Char", ObjChar.type)
            addConst("List", ObjList.type)
            addConst("Range", ObjRange.type)
            @Suppress("RemoveRedundantQualifierName")
            addConst("Callable", Statement.type)
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