package net.sergeych.lyng

import kotlinx.coroutines.delay
import net.sergeych.lyng.pacman.ImportManager
import kotlin.math.*

class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
//    private val catchReturn: Boolean = false,
) : Statement() {

    override suspend fun execute(scope: Scope): Obj {
        var lastResult: Obj = ObjVoid
        for (s in statements) {
            lastResult = s.execute(scope)
        }
        return lastResult
    }

    suspend fun execute() = execute(defaultImportManager.newModule())

    companion object {

        private val rootScope: Scope = Scope(null).apply {
            ObjException.addExceptionsToContext(this)
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
                    raiseError(ObjAssertionFailedException(this,"Assertion failed"))
            }

            addVoidFn("assertEquals") {
                val a = requiredArg<Obj>(0)
                val b = requiredArg<Obj>(1)
                if( a.compareTo(this, b) != 0 )
                    raiseError(ObjAssertionFailedException(this,"Assertion failed: ${a.inspect()} == ${b.inspect()}"))
            }
            addFn("assertThrows") {
                val code = requireOnlyArg<Statement>()
                val result =try {
                    code.execute(this)
                    null
                }
                catch( e: ExecutionError ) {
                    e.errorObject
                }
                catch (e: ScriptError) {
                    ObjNull
                }
                result ?: raiseError(ObjAssertionFailedException(this,"Expected exception but nothing was thrown"))
            }

            addVoidFn("delay") {
                delay((this.args.firstAndOnly().toDouble()/1000.0).roundToLong())
            }

            addConst("Object", rootObjectType)
            addConst("Real", ObjReal.type)
            addConst("String", ObjString.type)
            addConst("Int", ObjInt.type)
            addConst("Bool", ObjBool.type)
            addConst("Char", ObjChar.type)
            addConst("List", ObjList.type)
            addConst("Set", ObjSet.type)
            addConst("Range", ObjRange.type)
            addConst("Map", ObjMap.type)
            addConst("MapEntry", ObjMapEntry.type)
            @Suppress("RemoveRedundantQualifierName")
            addConst("Callable", Statement.type)
            // interfaces
            addConst("Iterable", ObjIterable)
            addConst("Collection", ObjCollection)
            addConst("Array", ObjArray)
            addConst("Class", ObjClassType)

            val pi = ObjReal(PI)
            addConst("π", pi)
            getOrCreateNamespace("Math").apply {
                addConst("PI", pi)
            }
        }

        val defaultImportManager: ImportManager by lazy {
            ImportManager(rootScope, SecurityManager.allowAll).apply {
                addPackage("lyng.buffer") {
                    it.addConst("Buffer", ObjBuffer.type)
                }
                addPackage("lyng.time") {
                    it.addConst("Instant", ObjInstant.type)
                    it.addConst("Duration", ObjDuration.type)
                    it.addFn("delay") {
                        val a = args.firstAndOnly()
                        when(a) {
                            is ObjInt -> delay(a.value * 1000)
                            is ObjReal -> delay((a.value * 1000).roundToLong())
                            is ObjDuration -> delay(a.duration)
                            else -> raiseIllegalArgument("Expected Duration, Int or Real, got ${a.inspect()}")
                        }
                        ObjVoid
                    }
                }
            }

        }

    }
}