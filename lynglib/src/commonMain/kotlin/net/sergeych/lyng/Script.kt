/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.stdlib_included.rootLyng
import net.sergeych.lynon.ObjLynonClass
import net.sergeych.mp_tools.globalDefer
import kotlin.math.*

@Suppress("TYPE_INTERSECTION_AS_REIFIED_WARNING")
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

    suspend fun execute() = execute(
        defaultImportManager.newStdScope()
    )

    companion object {

        /**
         * Create new scope using standard safe set of modules, using [defaultImportManager]. It is
         * suspended as first time calls requires compilation of standard library or other
         * asynchronous initialization.
         */
        suspend fun newScope(pos: Pos = Pos.builtIn) = defaultImportManager.newStdScope(pos)

        internal val rootScope: Scope = Scope(null).apply {
            ObjException.addExceptionsToContext(this)
            addFn("print") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + a.toString(this).value)
                    else print(a.toString(this).value)
                }
                ObjVoid
            }
            addFn("println") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + a.toString(this).value)
                    else print(a.toString(this).value)
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
                if( x is ObjInt) ObjInt( x.value.absoluteValue ) else ObjReal( x.toDouble().absoluteValue )
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
                    raiseError(ObjAssertionFailedException(this,"Assertion failed: ${a.inspect(this)} == ${b.inspect(this)}"))
            }
            addVoidFn("assertNotEquals") {
                val a = requiredArg<Obj>(0)
                val b = requiredArg<Obj>(1)
                if( a.compareTo(this, b) == 0 )
                    raiseError(ObjAssertionFailedException(this,"Assertion failed: ${a.inspect(this)} != ${b.inspect(this)}"))
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
                catch (_: ScriptError) {
                    ObjNull
                }
                result ?: raiseError(ObjAssertionFailedException(this,"Expected exception but nothing was thrown"))
            }

            addFn("dynamic") {
                ObjDynamic.create(this, requireOnlyArg())
            }

            addFn("require") {
                val condition = requiredArg<ObjBool>(0)
                if( !condition.value ) {
                    val message = args.list.getOrNull(1)?.toString() ?: "requirement not met"
                    raiseIllegalArgument(message)
                }
                ObjVoid
            }
            addFn("check") {
                val condition = requiredArg<ObjBool>(0)
                if( !condition.value ) {
                    val message = args.list.getOrNull(1)?.toString() ?: "check failed"
                    raiseIllegalState(message)
                }
                ObjVoid
            }
            addFn("traceScope") {
                this.trace(args.getOrNull(0)?.toString() ?: "")
                ObjVoid
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
            addConst("RingBuffer", ObjRingBuffer.type)
            addConst("Class", ObjClassType)

            addConst("Deferred", ObjDeferred.type)
            addConst("CompletableDeferred", ObjCompletableDeferred.type)
            addConst("Mutex", ObjMutex.type)

            addConst("Regex", ObjRegex.type)

            addFn("launch") {
                val callable = requireOnlyArg<Statement>()
                ObjDeferred(globalDefer {
                    callable.execute(this@addFn)
                })
            }

            addFn("yield") {
                yield()
                ObjVoid
            }

            addFn("flow") {
                // important is: current context contains closure often used in call;
                // we'll need it for the producer
                ObjFlow(requireOnlyArg<Statement>(), this)
            }

            val pi = ObjReal(PI)
            addConst("π", pi)
            getOrCreateNamespace("Math").apply {
                addConst("PI", pi)
            }
        }

        val defaultImportManager: ImportManager by lazy {
            ImportManager(rootScope, SecurityManager.allowAll).apply {
                addTextPackages(
                    rootLyng
                )
                addPackage("lyng.buffer") {
                    it.addConst("Buffer", ObjBuffer.type)
                    it.addConst("MutableBuffer", ObjMutableBuffer.type)
                }
                addPackage("lyng.serialization") {
                    it.addConst("Lynon", ObjLynonClass)
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
                            else -> raiseIllegalArgument("Expected Duration, Int or Real, got ${a.inspect(this)}")
                        }
                        ObjVoid
                    }
                }
            }

        }

    }
}