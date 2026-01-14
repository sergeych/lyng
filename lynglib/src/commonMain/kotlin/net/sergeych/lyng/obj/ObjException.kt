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

package net.sergeych.lyng.obj

import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
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
    val extraData: Obj = ObjNull,
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
            captureStackTrace(scope)
        }
    }

    constructor(scope: Scope, message: String) : this(Root, scope, ObjString(message))

    fun raise(): Nothing {
        throw ExecutionError(this, scope.pos, message.value)
    }

    override val objClass: ObjClass = exceptionClass

    /**
     * Tool to get kotlin string with error report including the Lyng stack trace.
     */
    suspend fun toStringWithStackTrace(): String {
        val l = getStackTrace().list
        return buildString {
            append(message.value)
            for (t in l)
                append("\n\tat ${t.toString(scope)}")
        }
    }

    override suspend fun defaultToString(scope: Scope): ObjString {
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

        suspend fun captureStackTrace(scope: Scope): ObjList {
            val result = ObjList()
            val maybeCls = scope.get("StackTraceEntry")?.value as? ObjClass
            var s: Scope? = scope
            var lastPos: Pos? = null
            while (s != null) {
                val pos = s.pos
                if (pos != lastPos && !pos.currentLine.isEmpty()) {
                    if( (lastPos == null || (lastPos.source != pos.source || lastPos.line != pos.line)) ) {
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
                            result.list += ObjString("#${pos.source.objSourceName}:${pos.line+1}:${pos.column+1}: ${pos.currentLine}")
                        }
                        lastPos = pos
                    }
                }
                s = s.parent
            }
            return result
        }

        class ExceptionClass(val name: String, vararg parents: ObjClass) : ObjClass(name, *parents) {
            init {
                constructorMeta = ArgsDeclaration(
                    listOf(ArgsDeclaration.Item("message", defaultValue = statement { ObjString(name) })),
                    Token.Type.RPAREN
                )
            }

            override suspend fun callOn(scope: Scope): Obj {
                val message = scope.args.getOrNull(0)?.toString(scope) ?: ObjString(name)
                val ex = ObjException(this, scope, message)
                ex.getStackTrace()
                return ex
            }

            override fun toString(): String = name

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
            instanceInitializers.add(statement {
                if (thisObj is ObjInstance) {
                    val msg = get("message")?.value ?: ObjString("Exception")
                    (thisObj as ObjInstance).instanceScope.addItem("Exception::message", false, msg)

                    val stack = captureStackTrace(this)
                    (thisObj as ObjInstance).instanceScope.addItem("Exception::stackTrace", false, stack)
                }
                ObjVoid
            })
            instanceConstructor = statement { ObjVoid }
            addPropertyDoc(
                name = "message",
                doc = "Human‑readable error message.",
                type = type("lyng.String"),
                moduleName = "lyng.stdlib",
                getter = {
                    when (val t = this.thisObj) {
                        is ObjException -> t.message
                        is ObjInstance -> t.instanceScope.get("Exception::message")?.value ?: ObjNull
                        else -> ObjNull
                    }
                }
            )
            addPropertyDoc(
                name = "extraData",
                doc = "Extra data associated with the exception.",
                type = type("lyng.Any", nullable = true),
                moduleName = "lyng.stdlib",
                getter = {
                    when (val t = this.thisObj) {
                        is ObjException -> t.extraData
                        else -> ObjNull
                    }
                }
            )
            addPropertyDoc(
                name = "stackTrace",
                doc = "Stack trace captured at throw site as a list of `StackTraceEntry`.",
                type = TypeGenericDoc(type("lyng.List"), listOf(type("lyng.StackTraceEntry"))),
                moduleName = "lyng.stdlib",
                getter = {
                    when (val t = this.thisObj) {
                        is ObjException -> t.getStackTrace()
                        is ObjInstance -> t.instanceScope.get("Exception::stackTrace")?.value as? ObjList ?: ObjList()
                        else -> ObjList()
                    }
                }
            )
            addFnDoc(
                name = "toString",
                doc = "Human‑readable string representation of the error.",
                returns = type("lyng.String"),
                moduleName = "lyng.stdlib"
            ) {
                val msg = when (val t = thisObj) {
                    is ObjException -> t.message.value
                    is ObjInstance -> (t.instanceScope.get("Exception::message")?.value as? ObjString)?.value
                        ?: t.objClass.className

                    else -> t.objClass.className
                }
                val stack = when (val t = thisObj) {
                    is ObjException -> t.getStackTrace()
                    is ObjInstance -> t.instanceScope.get("Exception::stackTrace")?.value as? ObjList ?: ObjList()
                    else -> ObjList()
                }
                val at = stack.list.firstOrNull()?.toString(this) ?: ObjString("(unknown)")
                ObjString("${thisObj.objClass.className}: $msg at $at")
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
                "IllegalAccessException",
                "UnknownException",
                "NotFoundException",
                "IllegalOperationException",
                "UnsetException",
                "NotImplementedException",
                "SyntaxError"
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

class ObjIllegalAccessException(scope: Scope, message: String = "access not allowed error") :
    ObjException("IllegalAccessException", scope, message)

class ObjUnknownException(scope: Scope, message: String = "access not allowed error") :
    ObjException("UnknownException", scope, message)

class ObjIllegalOperationException(scope: Scope, message: String = "Operation is illegal") :
    ObjException("IllegalOperationException", scope, message)

class ObjNotFoundException(scope: Scope, message: String = "not found") :
    ObjException("NotFoundException", scope, message)

class ObjUnsetException(scope: Scope, message: String = "property is unset (not initialized)") :
    ObjException("UnsetException", scope, message)

class ObjNotImplementedException(scope: Scope, message: String = "not implemented") :
    ObjException("NotImplementedException", scope, message)

/**
 * Check if the object is an instance of Lyng Exception class.
 */
fun Obj.isLyngException(): Boolean = isInstanceOf("Exception")

/**
 * Get the exception message.
 */
suspend fun Obj.getLyngExceptionMessage(scope: Scope? = null): String {
    require(this.isLyngException())
    val s = scope ?: Script.newScope()
    return invokeInstanceMethod(s, "message").toString(s).value
}

/**
 * Retrieves a detailed exception message including the stack trace for a Lyng exception.
 * This function is designed to handle objects identified as Lyng exceptions.
 *
 * @param scope the scope to be used for fetching the exception message and stack trace.
 *              If null, a new scope will be created.
 * @return a string combining the exception message, the location ("at"),
 *         and the formatted stack trace information.
 *         The stack trace details each frame using indentation for clarity.
 * @throws IllegalArgumentException if the object is not a Lyng exception.
 */
suspend fun Obj.getLyngExceptionMessageWithStackTrace(scope: Scope? = null,showDetails:Boolean=true): String {
    require(this.isLyngException())
    val s = scope ?: Script.newScope()
    val msg = getLyngExceptionMessage(s)
    val trace = getLyngExceptionStackTrace(s)
    var at = "unknown"
//    var firstLine = true
    val stack = if (!trace.list.isEmpty()) {
        val first = trace.list[0]
        at = (first.readField(s, "at").value as ObjString).value
        "\n" + trace.list.map { "    at " + it.toString(s).value }.joinToString("\n")
    } else ""
    return "$at: $msg$stack"
}

/**
 * Get the exception stack trace.
 */
suspend fun Obj.getLyngExceptionStackTrace(scope: Scope): ObjList =
    invokeInstanceMethod(scope, "stackTrace").cast(scope)

/**
 * Get the exception extra data.
 */
suspend fun Obj.getLyngExceptionExtraData(scope: Scope): Obj =
    invokeInstanceMethod(scope, "extraData")

/**
 * Get the exception as a formatted string with the primary throw site.
 */
suspend fun Obj.getLyngExceptionString(scope: Scope): String =
    invokeInstanceMethod(scope, "toString").toString(scope).value

/**
 * Rethrow this object as a Kotlin [ExecutionError] if it's an exception.
 */
suspend fun Obj.raiseAsExecutionError(scope: Scope? = null): Nothing {
    if (this is ObjException) raise()
    val sc = scope ?: Script.newScope()
    val msg = getLyngExceptionMessage(sc)
    val pos = (this as? ObjInstance)?.instanceScope?.pos ?: Pos.builtIn
    throw ExecutionError(this, pos, msg)
}
