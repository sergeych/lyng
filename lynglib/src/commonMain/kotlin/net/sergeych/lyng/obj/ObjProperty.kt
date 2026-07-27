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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.BytecodeCallable
import net.sergeych.lyng.BytecodeBodyProvider
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement
import net.sergeych.lyng.bytecode.BytecodeStatement
import net.sergeych.lyng.executeBytecodeWithSeed

/**
 * Property accessor storage. Per instructions, properties do NOT have
 * automatic backing fields. They are pure accessors.
 */
class ObjProperty(
    val name: String,
    val getter: Obj?,
    val setter: Obj?,
    private val declarationScope: Scope? = null
) : Obj() {

    fun withDeclarationScope(scope: Scope): ObjProperty =
        ObjProperty(name, getter, setter, scope)

    suspend fun callGetter(scope: Scope, instance: Obj, declaringClass: ObjClass? = null): Obj {
        val g = getter ?: scope.raiseError("property $name has no getter")
        // Execute getter in a child scope of the instance with 'this' properly set
        // Match extension function behavior (access to instance scope + call scope).
        val instanceScope = (instance as? ObjInstance)?.instanceScope ?: instance.autoInstanceScope(scope)
        val receiverCallScope = scope.applyClosure(instanceScope)
        val execBase = declarationScope?.let(receiverCallScope::applyClosure) ?: receiverCallScope
        val execScope = execBase.createChildScope(newThisObj = instance)
        execScope.currentClassCtx = declaringClass
        (g as? BytecodeCallable)?.callOnFast(execScope)?.let { return it }
        return when (g) {
            is BytecodeStatement -> executeBytecodeWithSeed(execScope, g, "property getter")
            is BytecodeBodyProvider -> {
                val body = g.bytecodeBody()
                if (body != null) executeBytecodeWithSeed(execScope, body, "property getter")
                else (g as? BytecodeCallable)?.callOnFast(execScope) ?: g.callOn(execScope)
            }
            is Statement -> (g as? BytecodeCallable)?.callOnFast(execScope) ?: g.callOn(execScope)
            else -> (g as? BytecodeCallable)?.callOnFast(execScope) ?: g.callOn(execScope)
        }
    }

    suspend fun callSetter(scope: Scope, instance: Obj, value: Obj, declaringClass: ObjClass? = null) {
        val s = setter ?: scope.raiseError("property $name has no setter")
        // Execute setter in a child scope of the instance with 'this' properly set and the value as an argument
        // Match extension function behavior (access to instance scope + call scope).
        val instanceScope = (instance as? ObjInstance)?.instanceScope ?: instance.autoInstanceScope(scope)
        val receiverCallScope = scope.applyClosure(instanceScope)
        val execBase = declarationScope?.let(receiverCallScope::applyClosure) ?: receiverCallScope
        val execScope = execBase.createChildScope(args = Arguments(value), newThisObj = instance)
        execScope.currentClassCtx = declaringClass
        (s as? BytecodeCallable)?.callOnFast(execScope)?.let { return }
        when (s) {
            is BytecodeStatement -> executeBytecodeWithSeed(execScope, s, "property setter")
            is BytecodeBodyProvider -> {
                val body = s.bytecodeBody()
                if (body != null) executeBytecodeWithSeed(execScope, body, "property setter")
                else (s as? BytecodeCallable)?.callOnFast(execScope) ?: s.callOn(execScope)
            }
            is Statement -> (s as? BytecodeCallable)?.callOnFast(execScope) ?: s.callOn(execScope)
            else -> (s as? BytecodeCallable)?.callOnFast(execScope) ?: s.callOn(execScope)
        }
    }

    override fun toString(): String = "Property($name)"
}
