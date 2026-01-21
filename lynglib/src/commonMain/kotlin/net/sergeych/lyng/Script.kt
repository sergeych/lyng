/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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
import net.sergeych.lyng.Script.Companion.defaultImportManager
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.pacman.ModuleBuilder
import net.sergeych.lyng.stdlib_included.rootLyng
import net.sergeych.lynon.ObjLynonClass
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
            addConst("Unset", ObjUnset)
            addFn("print", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    for ((i, a) in scp.args.withIndex()) {
                        if (i > 0) print(' ' + a.toString(scp).value)
                        else print(a.toString(scp).value)
                    }
                    return ObjVoid
                }
            })
            addFn("println", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    for ((i, a) in scp.args.withIndex()) {
                        if (i > 0) print(' ' + a.toString(scp).value)
                        else print(a.toString(scp).value)
                    }
                    println()
                    return ObjVoid
                }
            })
            addFn("floor", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val x = scp.args.firstAndOnly()
                    return (if (x is ObjInt) x
                    else ObjReal(floor(x.toDouble())))
                }
            })
            addFn("ceil", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val x = scp.args.firstAndOnly()
                    return (if (x is ObjInt) x
                    else ObjReal(ceil(x.toDouble())))
                }
            })
            addFn("round", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val x = scp.args.firstAndOnly()
                    return (if (x is ObjInt) x
                    else ObjReal(round(x.toDouble())))
                }
            })

            addFn("sin", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(sin(scp.args.firstAndOnly().toDouble())) })
            addFn("cos", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(cos(scp.args.firstAndOnly().toDouble())) })
            addFn("tan", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(tan(scp.args.firstAndOnly().toDouble())) })
            addFn("asin", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(asin(scp.args.firstAndOnly().toDouble())) })
            addFn("acos", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(acos(scp.args.firstAndOnly().toDouble())) })
            addFn("atan", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(atan(scp.args.firstAndOnly().toDouble())) })

            addFn("sinh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(sinh(scp.args.firstAndOnly().toDouble())) })
            addFn("cosh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(cosh(scp.args.firstAndOnly().toDouble())) })
            addFn("tanh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(tanh(scp.args.firstAndOnly().toDouble())) })
            addFn("asinh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(asinh(scp.args.firstAndOnly().toDouble())) })
            addFn("acosh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(acosh(scp.args.firstAndOnly().toDouble())) })
            addFn("atanh", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(atanh(scp.args.firstAndOnly().toDouble())) })

            addFn("exp", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(exp(scp.args.firstAndOnly().toDouble())) })
            addFn("ln", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(ln(scp.args.firstAndOnly().toDouble())) })

            addFn("log10", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(log10(scp.args.firstAndOnly().toDouble())) })

            addFn("log2", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(log2(scp.args.firstAndOnly().toDouble())) })

            addFn("pow", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    scp.requireExactCount(2)
                    return ObjReal((scp.args[0].toDouble()).pow(scp.args[1].toDouble()))
                }
            })
            addFn("sqrt", fn = object : ScopeCallable { override suspend fun call(scp: Scope): Obj = ObjReal(sqrt(scp.args.firstAndOnly().toDouble())) })
            addFn("abs", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val x = scp.args.firstAndOnly()
                    return if (x is ObjInt) ObjInt(x.value.absoluteValue) else ObjReal(x.toDouble().absoluteValue)
                }
            })

            addFnDoc<Obj>(
                "clamp",
                doc = "Clamps the value within the specified range. If the value is outside the range, it is set to the nearest boundary. Respects inclusive/exclusive range ends.",
                params = listOf(ParamDoc("value"), ParamDoc("range")),
                moduleName = "lyng.stdlib",
                fn = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val value = scp.requiredArg<Obj>(0)
                        val range = scp.requiredArg<ObjRange>(1)

                        var result = value
                        if (range.start != null && !range.start.isNull) {
                            if (result.compareTo(scp, range.start) < 0) {
                                result = range.start
                            }
                        }
                        if (range.end != null && !range.end.isNull) {
                            val cmp = range.end.compareTo(scp, result)
                            if (range.isEndInclusive) {
                                if (cmp < 0) result = range.end
                            } else {
                                if (cmp <= 0) {
                                    if (range.end is ObjInt) {
                                        result = ObjInt.of(range.end.value - 1)
                                    } else if (range.end is ObjChar) {
                                        result = ObjChar((range.end.value.code - 1).toChar())
                                    } else {
                                        // For types where we can't easily find "previous" value (like Real),
                                        // we just return the exclusive boundary as a fallback.
                                        result = range.end
                                    }
                                }
                            }
                        }
                        return result
                    }
                }
            )

            addVoidFn("assert", fn = object : VoidScopeCallable {
                override suspend fun call(scp: Scope) {
                    val cond = scp.requiredArg<ObjBool>(0)
                    val message = if (scp.args.size > 1)
                        ": " + (scp.args[1] as Statement).execute(scp).toString(scp).value
                    else ""
                    if (!cond.value == true)
                        scp.raiseError(ObjAssertionFailedException(scp, "Assertion failed$message"))
                }
            })

            addVoidFn("assertEquals", fn = object : VoidScopeCallable {
                override suspend fun call(scp: Scope) {
                    val a = scp.requiredArg<Obj>(0)
                    val b = scp.requiredArg<Obj>(1)
                    if (a.compareTo(scp, b) != 0)
                        scp.raiseError(
                            ObjAssertionFailedException(
                                scp,
                                "Assertion failed: ${a.inspect(scp)} == ${b.inspect(scp)}"
                            )
                        )
                }
            })
            // alias used in tests
            addVoidFn("assertEqual", fn = object : VoidScopeCallable {
                override suspend fun call(scp: Scope) {
                    val a = scp.requiredArg<Obj>(0)
                    val b = scp.requiredArg<Obj>(1)
                    if (a.compareTo(scp, b) != 0)
                        scp.raiseError(
                            ObjAssertionFailedException(
                                scp,
                                "Assertion failed: ${a.inspect(scp)} == ${b.inspect(scp)}"
                            )
                        )
                }
            })
            addVoidFn("assertNotEquals", fn = object : VoidScopeCallable {
                override suspend fun call(scp: Scope) {
                    val a = scp.requiredArg<Obj>(0)
                    val b = scp.requiredArg<Obj>(1)
                    if (a.compareTo(scp, b) == 0)
                        scp.raiseError(
                            ObjAssertionFailedException(
                                scp,
                                "Assertion failed: ${a.inspect(scp)} != ${b.inspect(scp)}"
                            )
                        )
                }
            })
            addFnDoc<Obj>(
                "assertThrows",
                doc = """
                    Asserts that the provided code block throws an exception, with or without exception: 
                    ```lyng
                        assertThrows { /* ode */ }
                        assertThrows(IllegalArgumentException) { /* code */ }
                    ```
                    If an expected exception class is provided,
                    it checks that the thrown exception is of that class. If no expected class is provided, any exception
                    will be accepted.
                """.trimIndent(),
                fn = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val code: Statement
                        val expectedClass: ObjClass?
                        when (scp.args.size) {
                            1 -> {
                                code = scp.requiredArg<Statement>(0)
                                expectedClass = null
                            }

                            2 -> {
                                code = scp.requiredArg<Statement>(1)
                                expectedClass = scp.requiredArg<ObjClass>(0)
                            }

                            else -> scp.raiseIllegalArgument("Expected 1 or 2 arguments, got ${scp.args.size}")
                        }
                        val result = try {
                            code.execute(scp)
                            null
                        } catch (e: ExecutionError) {
                            e.errorObject
                        } catch (_: ScriptError) {
                            ObjNull
                        }
                        if (result == null) scp.raiseError(
                            ObjAssertionFailedException(
                                scp,
                                "Expected exception but nothing was thrown"
                            )
                        )
                        expectedClass?.let {
                            if (!result.isInstanceOf(it)) {
                                val actual = if (result is ObjException) result.exceptionClass else result.objClass
                                scp.raiseError("Expected $it, got $actual")
                            }
                        }
                        return result ?: ObjNull
                    }
                }
            )

            addFn("dynamic", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    return ObjDynamic.create(scp, scp.requireOnlyArg())
                }
            })

            val root = this
            val mathClass = ObjClass("Math").apply {
                addFn("sqrt", fn = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        return ObjReal(sqrt(scp.args.firstAndOnly().toDouble()))
                    }
                })
            }
            addItem("Math", false, ObjInstance(mathClass).apply {
                instanceScope = Scope(root, thisObj = this)
            })

            addFn("require", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val condition = scp.requiredArg<ObjBool>(0)
                    if (!condition.value) {
                        var message = scp.args.list.getOrNull(1)
                        if (message is Statement) message = message.execute(scp)
                        scp.raiseIllegalArgument(message?.toString() ?: "requirement not met")
                    }
                    return ObjVoid
                }
            })
            addFn("check", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    val condition = scp.requiredArg<ObjBool>(0)
                    if (!condition.value) {
                        var message = scp.args.list.getOrNull(1)
                        if (message is Statement) message = message.execute(scp)
                        scp.raiseIllegalState(message?.toString() ?: "check failed")
                    }
                    return ObjVoid
                }
            })
            addFn("traceScope", fn = object : ScopeCallable {
                override suspend fun call(scp: Scope): Obj {
                    scp.trace(scp.args.getOrNull(0)?.toString() ?: "")
                    return ObjVoid
                }
            })

/*
            addVoidFn("delay") {
                val a = args.firstAndOnly()
                when (a) {
                    is ObjInt -> delay(a.value)
                    is ObjReal -> delay((a.value * 1000).roundToLong())
                    is ObjDuration -> delay(a.duration)
                    else -> raiseIllegalArgument("Expected Int, Real or Duration, got ${a.inspect(this)}")
                }
            }
*/
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
            addConst("Flow", ObjFlow.type)

            addConst("Regex", ObjRegex.type)
/*
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
*/
            val pi = ObjReal(PI)
            addConstDoc(
                name = "π",
                value = pi,
                doc = "The mathematical constant pi (π).",
                type = type("lyng.Real")
            )
            getOrCreateNamespace("Math").apply {
                addConstDoc(
                    name = "PI",
                    value = pi,
                    doc = "The mathematical constant pi (π) in the Math namespace.",
                    type = type("lyng.Real")
                )
            }
        }

        val defaultImportManager: ImportManager by lazy {
            ImportManager(rootScope, SecurityManager.allowAll).apply {
                addTextPackages(
                    rootLyng
                )
                addPackage("lyng.buffer", object : ModuleBuilder {
                    override suspend fun build(ms: ModuleScope) {
                        ms.addConstDoc(
                            name = "Buffer",
                            value = ObjBuffer.type,
                            doc = "Immutable sequence of bytes. Use for binary data and IO.",
                            type = type("lyng.Class")
                        )
                        ms.addConstDoc(
                            name = "MutableBuffer",
                            value = ObjMutableBuffer.type,
                            doc = "Mutable byte buffer. Supports in-place modifications.",
                            type = type("lyng.Class")
                        )
                    }
                })
                addPackage("lyng.serialization", object : ModuleBuilder {
                    override suspend fun build(ms: ModuleScope) {
                        ms.addConstDoc(
                            name = "Lynon",
                            value = ObjLynonClass,
                            doc = "Lynon serialization utilities: encode/decode data structures to a portable binary/text form.",
                            type = type("lyng.Class")
                        )
                    }
                })
                addPackage("lyng.time", object : ModuleBuilder {
                    override suspend fun build(ms: ModuleScope) {
                        ms.addConstDoc(
                            name = "Instant",
                            value = ObjInstant.type,
                            doc = "Point in time (epoch-based).",
                            type = type("lyng.Class")
                        )
                        ms.addConstDoc(
                            name = "DateTime",
                            value = ObjDateTime.type,
                            doc = "Point in time in a specific time zone.",
                            type = type("lyng.Class")
                        )
                        ms.addConstDoc(
                            name = "Duration",
                            value = ObjDuration.type,
                            doc = "Time duration with millisecond precision.",
                            type = type("lyng.Class")
                        )
                        ms.addVoidFnDoc(
                            "delay",
                            doc = "Suspend for the given time. Accepts Duration, Int seconds, or Real seconds.",
                            fn = object : VoidScopeCallable {
                                override suspend fun call(scp: Scope) {
                                    val a = scp.args.firstAndOnly()
                                    when (a) {
                                        is ObjInt -> delay(a.value * 1000)
                                        is ObjReal -> delay((a.value * 1000).roundToLong())
                                        is ObjDuration -> delay(a.duration)
                                        else -> scp.raiseIllegalArgument("Expected Duration, Int or Real, got ${a.inspect(scp)}")
                                    }
                                }
                            }
                        )
                    }
                })
            }

        }

    }
}