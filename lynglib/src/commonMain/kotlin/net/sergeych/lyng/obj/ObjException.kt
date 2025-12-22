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

package net.sergeych.lyng.obj

import net.sergeych.bintools.encodeToHex
import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addConstDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import net.sergeych.mptools.CachedExpression
import net.sergeych.synctools.ProtectedOp
import net.sergeych.synctools.withLock
import kotlin.contracts.ExperimentalContracts

/**
 * Note on [getStackTrace]. If [useStackTrace] is not null, it is used instead. Otherwise, it is calculated
 * from the current scope, which is treated as an exception scope. It is used to restore a serialized
 * exception with stack trace; the scope of the deserialized exception is not valid
 * for stack unwinding.
 */
open class ObjException(
    val exceptionClass: ExceptionClass,
    val scope: Scope,
    val message: ObjString,
    @Suppress("unused") val extraData: Obj = ObjNull,
    val useStackTrace: ObjList? = null
) : Obj() {
    constructor(name: String, scope: Scope, message: String) : this(
        getOrCreateExceptionClass(name),
        scope,
        ObjString(message)
    )

    private val cachedStackTrace = CachedExpression(initialValue = useStackTrace)

    suspend fun getStackTrace(): ObjList {
        return cachedStackTrace.get {
            val result = ObjList()
            val maybeCls = scope.get("StackTraceEntry")?.value as? ObjClass
            var s: Scope? = scope
            var lastPos: Pos? = null
            while (s != null) {
                val pos = s.pos
                if (pos != lastPos && !pos.currentLine.isEmpty()) {
                    if (maybeCls != null) {
                        result.list += maybeCls.callWithArgs(
                            scope,
                            pos.source.objSourceName,
                            ObjInt(pos.line.toLong()),
                            ObjInt(pos.column.toLong()),
                            ObjString(pos.currentLine)
                        )
                    } else {
                        // Fallback textual entry if StackTraceEntry class is not available in this scope
                        result.list += ObjString("${pos.source.objSourceName}:${pos.line}:${pos.column}: ${pos.currentLine}")
                    }
                }
                s = s.parent
                lastPos = pos
            }
            result
        }
    }

    constructor(scope: Scope, message: String) : this(Root, scope, ObjString(message))

    fun raise(): Nothing {
        throw ExecutionError(this)
    }

    override val objClass: ObjClass = exceptionClass

    override suspend fun toString(scope: Scope, calledFromLyng: Boolean): ObjString {
        val at = getStackTrace().list.firstOrNull()?.toString(scope)
            ?: ObjString("(unknown)")
        return ObjString("${objClass.className}: $message at $at")
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeAny(scope, exceptionClass.classNameObj)
        encoder.encodeAny(scope, message)
        encoder.encodeAny(scope, extraData)
        encoder.encodeAny(scope, getStackTrace())
    }


    companion object {

        class ExceptionClass(val name: String, vararg parents: ObjClass) : ObjClass(name, *parents) {
            override suspend fun callOn(scope: Scope): Obj {
                val message = scope.args.getOrNull(0)?.toString(scope) ?: ObjString(name)
                return ObjException(this, scope, message)
            }

            override fun toString(): String = "ExceptionClass[$name]@${hashCode().encodeToHex()}"

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return try {
                    val lyngClass = decoder.decodeAnyAs<ObjString>(scope).value.let {
                        ((scope[it] ?: scope.raiseIllegalArgument("Unknown exception class: $it"))
                            .value as? ExceptionClass)
                            ?: scope.raiseIllegalArgument("Not an exception class: $it")
                    }
                    ObjException(
                        lyngClass,
                        scope,
                        decoder.decodeAnyAs<ObjString>(scope),
                        decoder.decodeAny(scope),
                        decoder.decodeAnyAs<ObjList>(scope)
                    )
                } catch (e: ScriptError) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    scope.raiseIllegalArgument("Failed to deserialize exception: ${e.message}")
                }
            }
        }

        val Root = ExceptionClass("Exception").apply {
            addConstDoc(
                name = "message",
                value = statement {
                    (thisObj as ObjException).message.toObj()
                },
                doc = "Human‑readable error message.",
                type = type("lyng.String"),
                moduleName = "lyng.stdlib"
            )
            addFnDoc(
                name = "stackTrace",
                doc = "Stack trace captured at throw site as a list of `StackTraceEntry`.",
                returns = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.StackTraceEntry"))),
                moduleName = "lyng.stdlib"
            ) {
                (thisObj as ObjException).getStackTrace()
            }
        }

        private val op = ProtectedOp()
        private val existingErrorClasses = mutableMapOf<String, ExceptionClass>()


        @OptIn(ExperimentalContracts::class)
        protected fun getOrCreateExceptionClass(name: String): ExceptionClass {
            return op.withLock {
                existingErrorClasses.getOrPut(name) {
                    ExceptionClass(name, Root)
                }
            }
        }

        /**
         * Get [ObjClass] for error class by name if exists.
         */
        @OptIn(ExperimentalContracts::class)
        fun getErrorClass(name: String): ObjClass? = op.withLock {
            existingErrorClasses[name]
        }

        fun addExceptionsToContext(scope: Scope) {
            scope.addConst("Exception", Root)
            existingErrorClasses["Exception"] = Root
            for (name in listOf(
                "NullReferenceException",
                "AssertionFailedException",
                "ClassCastException",
                "IndexOutOfBoundsException",
                "IllegalArgumentException",
                "IllegalStateException",
                "NoSuchElementException",
                "IllegalAssignmentException",
                "SymbolNotDefinedException",
                "IterationEndException",
                "AccessException",
                "UnknownException",
                "NotFoundException",
                "IllegalOperationException"
            )) {
                scope.addConst(name, getOrCreateExceptionClass(name))
            }
            // Backward compatibility alias used in older tests/docs
            val snd = getOrCreateExceptionClass("SymbolNotDefinedException")
            scope.addConst("SymbolNotFound", snd)
            existingErrorClasses["SymbolNotFound"] = snd
        }
    }
}

class ObjNullReferenceException(scope: Scope) : ObjException("NullReferenceException", scope, "object is null")

class ObjAssertionFailedException(scope: Scope, message: String) :
    ObjException("AssertionFailedException", scope, message)

class ObjClassCastException(scope: Scope, message: String) : ObjException("ClassCastException", scope, message)
class ObjIndexOutOfBoundsException(scope: Scope, message: String = "index out of bounds") :
    ObjException("IndexOutOfBoundsException", scope, message)

class ObjIllegalArgumentException(scope: Scope, message: String = "illegal argument") :
    ObjException("IllegalArgumentException", scope, message)

class ObjIllegalStateException(scope: Scope, message: String = "illegal state") :
    ObjException("IllegalStateException", scope, message)

@Suppress("unused")
class ObjNoSuchElementException(scope: Scope, message: String = "no such element") :
    ObjException("NoSuchElementException", scope, message)

class ObjIllegalAssignmentException(scope: Scope, message: String = "illegal assignment") :
    ObjException("IllegalAssignmentException", scope, message)

class ObjSymbolNotDefinedException(scope: Scope, message: String = "symbol is not defined") :
    ObjException("SymbolNotDefinedException", scope, message)

class ObjIterationFinishedException(scope: Scope) :
    ObjException("IterationEndException", scope, "iteration finished")

class ObjAccessException(scope: Scope, message: String = "access not allowed error") :
    ObjException("AccessException", scope, message)

class ObjUnknownException(scope: Scope, message: String = "access not allowed error") :
    ObjException("UnknownException", scope, message)

class ObjIllegalOperationException(scope: Scope, message: String = "Operation is illegal") :
    ObjException("IllegalOperationException", scope, message)

class ObjNotFoundException(scope: Scope, message: String = "not found") :
    ObjException("NotFoundException", scope, message)
