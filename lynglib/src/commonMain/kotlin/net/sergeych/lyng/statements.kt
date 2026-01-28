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

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjRange
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.toBool
import net.sergeych.lyng.obj.toInt
import net.sergeych.lyng.obj.toLong

fun String.toSource(name: String = "eval"): Source = Source(name, this)

sealed class ObjType {
    object Any : ObjType()
    object Void: ObjType()

    companion object {
    }
}


@Suppress("unused")
abstract class Statement(
    val isStaticConst: Boolean = false,
    override val isConst: Boolean = false,
    val returnType: ObjType = ObjType.Any
) : Obj() {

    override val objClass: ObjClass = type

    abstract val pos: Pos
    abstract suspend fun execute(scope: Scope): Obj

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if( other === this ) return 0
        return -3
    }

    override suspend fun callOn(scope: Scope): Obj {
        return execute(scope)
    }

    override fun toString(): String = "Callable@${this.hashCode()}"

    companion object {
        val type = ObjClass("Callable")
    }

    suspend fun call(scope: Scope, vararg args: Obj) = execute(scope.createChildScope(args =  Arguments(*args)))

}

class IfStatement(
    val condition: Statement,
    val ifBody: Statement,
    val elseBody: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return if (condition.execute(scope).toBool()) {
            ifBody.execute(scope)
        } else {
            elseBody?.execute(scope) ?: ObjVoid
        }
    }
}

data class ConstIntRange(val start: Long, val endExclusive: Long)

class ForInStatement(
    val loopVarName: String,
    val source: Statement,
    val constRange: ConstIntRange?,
    val body: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val canBreak: Boolean,
    val loopSlotPlan: Map<String, Int>,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val forContext = scope.createChildScope(pos)
        if (loopSlotPlan.isNotEmpty()) {
            forContext.applySlotPlan(loopSlotPlan)
        }

        val loopSO = forContext.addItem(loopVarName, true, ObjNull)
        val loopSlotIndex = forContext.getSlotIndexOf(loopVarName) ?: -1

        if (constRange != null && PerfFlags.PRIMITIVE_FASTOPS) {
            return loopIntRange(
                forContext,
                constRange.start,
                constRange.endExclusive,
                loopSO,
                loopSlotIndex,
                body,
                elseStatement,
                label,
                canBreak
            )
        }

        val sourceObj = source.execute(forContext)
        return if (sourceObj is ObjRange && sourceObj.isIntRange && PerfFlags.PRIMITIVE_FASTOPS) {
            loopIntRange(
                forContext,
                sourceObj.start!!.toLong(),
                if (sourceObj.isEndInclusive) sourceObj.end!!.toLong() + 1 else sourceObj.end!!.toLong(),
                loopSO,
                loopSlotIndex,
                body,
                elseStatement,
                label,
                canBreak
            )
        } else if (sourceObj.isInstanceOf(ObjIterable)) {
            loopIterable(forContext, sourceObj, loopSO, body, elseStatement, label, canBreak)
        } else {
            val size = runCatching { sourceObj.readField(forContext, "size").value.toInt() }
                .getOrElse {
                    throw ScriptError(
                        pos,
                        "object is not enumerable: no size in $sourceObj",
                        it
                    )
                }

            var result: Obj = ObjVoid
            var breakCaught = false

            if (size > 0) {
                var current = runCatching { sourceObj.getAt(forContext, ObjInt.of(0)) }
                    .getOrElse {
                        throw ScriptError(
                            pos,
                            "object is not enumerable: no index access for ${sourceObj.inspect(scope)}",
                            it
                        )
                    }
                var index = 0
                while (true) {
                    loopSO.value = current
                    try {
                        result = body.execute(forContext)
                    } catch (lbe: LoopBreakContinueException) {
                        if (lbe.label == label || lbe.label == null) {
                            breakCaught = true
                            if (lbe.doContinue) continue
                            result = lbe.result
                            break
                        } else {
                            throw lbe
                        }
                    }
                    if (++index >= size) break
                    current = sourceObj.getAt(forContext, ObjInt.of(index.toLong()))
                }
            }
            if (!breakCaught && elseStatement != null) {
                result = elseStatement.execute(scope)
            }
            result
        }
    }

    private suspend fun loopIntRange(
        forScope: Scope,
        start: Long,
        end: Long,
        loopVar: ObjRecord,
        loopSlotIndex: Int,
        body: Statement,
        elseStatement: Statement?,
        label: String?,
        catchBreak: Boolean,
    ): Obj {
        var result: Obj = ObjVoid
        val cacheLow = ObjInt.CACHE_LOW
        val cacheHigh = ObjInt.CACHE_HIGH
        val useCache = start >= cacheLow && end <= cacheHigh + 1
        val cache = if (useCache) ObjInt.cacheArray() else null
        val useSlot = loopSlotIndex >= 0
        if (catchBreak) {
            if (useCache && cache != null) {
                var i = start
                while (i < end) {
                    val v = cache[(i - cacheLow).toInt()]
                    if (useSlot) forScope.setSlotValue(loopSlotIndex, v) else loopVar.value = v
                    try {
                        result = body.execute(forScope)
                    } catch (lbe: LoopBreakContinueException) {
                        if (lbe.label == label || lbe.label == null) {
                            if (lbe.doContinue) {
                                i++
                                continue
                            }
                            return lbe.result
                        }
                        throw lbe
                    }
                    i++
                }
            } else {
                for (i in start..<end) {
                    val v = ObjInt.of(i)
                    if (useSlot) forScope.setSlotValue(loopSlotIndex, v) else loopVar.value = v
                    try {
                        result = body.execute(forScope)
                    } catch (lbe: LoopBreakContinueException) {
                        if (lbe.label == label || lbe.label == null) {
                            if (lbe.doContinue) continue
                            return lbe.result
                        }
                        throw lbe
                    }
                }
            }
        } else {
            if (useCache && cache != null) {
                var i = start
                while (i < end) {
                    val v = cache[(i - cacheLow).toInt()]
                    if (useSlot) forScope.setSlotValue(loopSlotIndex, v) else loopVar.value = v
                    result = body.execute(forScope)
                    i++
                }
            } else {
                for (i in start..<end) {
                    val v = ObjInt.of(i)
                    if (useSlot) forScope.setSlotValue(loopSlotIndex, v) else loopVar.value = v
                    result = body.execute(forScope)
                }
            }
        }
        return elseStatement?.execute(forScope) ?: result
    }

    private suspend fun loopIterable(
        forScope: Scope,
        sourceObj: Obj,
        loopVar: ObjRecord,
        body: Statement,
        elseStatement: Statement?,
        label: String?,
        catchBreak: Boolean,
    ): Obj {
        var result: Obj = ObjVoid
        var breakCaught = false
        sourceObj.enumerate(forScope) { item ->
            loopVar.value = item
            if (catchBreak) {
                try {
                    result = body.execute(forScope)
                    true
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        breakCaught = true
                        if (lbe.doContinue) true else {
                            result = lbe.result
                            false
                        }
                    } else {
                        throw lbe
                    }
                }
            } else {
                result = body.execute(forScope)
                true
            }
        }
        if (!breakCaught && elseStatement != null) {
            result = elseStatement.execute(forScope)
        }
        return result
    }
}

class WhileStatement(
    val condition: Statement,
    val body: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val canBreak: Boolean,
    val loopSlotPlan: Map<String, Int>,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        var result: Obj = ObjVoid
        var wasBroken = false
        while (condition.execute(scope).toBool()) {
            val loopScope = scope.createChildScope().apply { skipScopeCreation = true }
            if (canBreak) {
                try {
                    result = body.execute(loopScope)
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) continue
                        result = lbe.result
                        wasBroken = true
                        break
                    } else {
                        throw lbe
                    }
                }
            } else {
                result = body.execute(loopScope)
            }
        }
        if (!wasBroken) elseStatement?.let { s -> result = s.execute(scope) }
        return result
    }
}

class DoWhileStatement(
    val body: Statement,
    val condition: Statement,
    val elseStatement: Statement?,
    val label: String?,
    val loopSlotPlan: Map<String, Int>,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        var wasBroken = false
        var result: Obj = ObjVoid
        while (true) {
            val doScope = scope.createChildScope().apply { skipScopeCreation = true }
            try {
                result = body.execute(doScope)
            } catch (e: LoopBreakContinueException) {
                if (e.label == label || e.label == null) {
                    if (!e.doContinue) {
                        result = e.result
                        wasBroken = true
                        break
                    }
                    // continue: fall through to condition check
                } else {
                    throw e
                }
            }
            if (!condition.execute(doScope).toBool()) {
                break
            }
        }
        if (!wasBroken) elseStatement?.let { s -> result = s.execute(scope) }
        return result
    }
}

class BreakStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val returnValue = resultExpr?.execute(scope)
        throw LoopBreakContinueException(
            doContinue = false,
            label = label,
            result = returnValue ?: ObjVoid
        )
    }
}

class ContinueStatement(
    val label: String?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        throw LoopBreakContinueException(
            doContinue = true,
            label = label,
        )
    }
}

class ReturnStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        val returnValue = resultExpr?.execute(scope) ?: ObjVoid
        throw ReturnException(returnValue, label)
    }
}

class ThrowStatement(
    val throwExpr: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        var errorObject = throwExpr.execute(scope)
        val throwScope = scope.createChildScope(pos = pos)
        if (errorObject is ObjString) {
            errorObject = ObjException(throwScope, errorObject.value).apply { getStackTrace() }
        }
        if (!errorObject.isInstanceOf(ObjException.Root)) {
            throwScope.raiseError("this is not an exception object: $errorObject")
        }
        if (errorObject is ObjException) {
            errorObject = ObjException(
                errorObject.exceptionClass,
                throwScope,
                errorObject.message,
                errorObject.extraData,
                errorObject.useStackTrace
            ).apply { getStackTrace() }
            throwScope.raiseError(errorObject)
        } else {
            val msg = errorObject.invokeInstanceMethod(scope, "message").toString(scope).value
            throwScope.raiseError(errorObject, pos, msg)
        }
        return ObjVoid
    }
}

class ToBoolStatement(
    val expr: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return if (expr.execute(scope).toBool()) net.sergeych.lyng.obj.ObjTrue else net.sergeych.lyng.obj.ObjFalse
    }
}

class ExpressionStatement(
    val ref: net.sergeych.lyng.obj.ObjRef,
    override val pos: Pos
) : Statement() {
    override suspend fun execute(scope: Scope): Obj = ref.evalValue(scope)
}

fun Statement.raise(text: String): Nothing {
    throw ScriptError(pos, text)
}

@Suppress("unused")
fun Statement.require(cond: Boolean, message: () -> String) {
    if (!cond) raise(message())
}

fun statement(pos: Pos, isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend (Scope) -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = pos
        override suspend fun execute(scope: Scope): Obj = f(scope)
    }

fun statement(isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend Scope.() -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = Pos.builtIn
        override suspend fun execute(scope: Scope): Obj = f(scope)
    }

object NopStatement: Statement(true, true, ObjType.Void) {
    override val pos: Pos = Pos.builtIn

    override suspend fun execute(scope: Scope): Obj = ObjVoid
}
