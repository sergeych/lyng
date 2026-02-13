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
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjNull
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

    protected fun interpreterDisabled(scope: Scope, label: String): Nothing {
        return scope.raiseIllegalState("interpreter execution is not supported; $label requires bytecode")
    }

}

class IfStatement(
    val condition: Statement,
    val ifBody: Statement,
    val elseBody: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "if statement")
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
    val loopScopeId: Int,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "for-in statement")
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
        return interpreterDisabled(scope, "while statement")
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
        return interpreterDisabled(scope, "do-while statement")
    }
}

class BreakStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "break statement")
    }
}

class ContinueStatement(
    val label: String?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "continue statement")
    }
}

class ReturnStatement(
    val label: String?,
    val resultExpr: Statement?,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "return statement")
    }
}

class ThrowStatement(
    val throwExpr: Statement,
    override val pos: Pos,
) : Statement() {
    override suspend fun execute(scope: Scope): Obj {
        return interpreterDisabled(scope, "throw statement")
    }
}

class ExpressionStatement(
    val ref: net.sergeych.lyng.obj.ObjRef,
    override val pos: Pos
) : Statement() {
    override suspend fun execute(scope: Scope): Obj = interpreterDisabled(scope, "expression statement")
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
        override suspend fun execute(scope: Scope): Obj = interpreterDisabled(scope, "statement bridge")
    }

fun statement(isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend Scope.() -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = Pos.builtIn
        override suspend fun execute(scope: Scope): Obj = interpreterDisabled(scope, "statement bridge")
    }

object NopStatement: Statement(true, true, ObjType.Void) {
    override val pos: Pos = Pos.builtIn

    override suspend fun execute(scope: Scope): Obj = interpreterDisabled(scope, "nop statement")
}
