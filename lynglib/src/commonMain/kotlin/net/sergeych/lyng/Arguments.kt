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

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjList

 data class ParsedArgument(val value: Statement, val pos: Pos, val isSplat: Boolean = false)
 
 suspend fun Collection<ParsedArgument>.toArguments(scope: Scope, tailBlockMode: Boolean): Arguments {
     // Small-arity fast path (no splats) to reduce allocations
     if (PerfFlags.ARG_BUILDER) {
         var hasSplat = false
         var count = 0
         for (pa in this) {
             if (pa.isSplat) { hasSplat = true; break }
             count++
             if (count > 3) break
         }
         if (!hasSplat && count == this.size) {
             val quick = when (count) {
                 0 -> Arguments.EMPTY
                 1 -> Arguments(listOf(this.elementAt(0).value.execute(scope)), tailBlockMode)
                 2 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     Arguments(listOf(a0, a1), tailBlockMode)
                 }
                 3 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     Arguments(listOf(a0, a1, a2), tailBlockMode)
                 }
                 4 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3), tailBlockMode)
                 }
                 5 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4), tailBlockMode)
                 }
                 else -> null
             }
             if (quick != null) return quick
         }
     }
     // General path with builder or simple list fallback
     if (PerfFlags.ARG_BUILDER) {
         val b = ArgBuilderProvider.acquire()
         try {
             b.reset(this.size)
             for (x in this) {
                 val value = x.value.execute(scope)
                 if (x.isSplat) {
                     when {
                         value is ObjList -> {
                             b.addAll(value.list)
                         }
                         value.isInstanceOf(ObjIterable) -> {
                             val i = (value.invokeInstanceMethod(scope, "toList") as ObjList).list
                             b.addAll(i)
                         }
                         else -> scope.raiseClassCastError("expected list of objects for splat argument")
                     }
                 } else {
                     b.add(value)
                 }
             }
             return b.build(tailBlockMode)
         } finally {
             b.release()
         }
     } else {
         val list: MutableList<Obj> = mutableListOf()
         for (x in this) {
             val value = x.value.execute(scope)
             if (x.isSplat) {
                 when {
                     value is ObjList -> list.addAll(value.list)
                     value.isInstanceOf(ObjIterable) -> {
                         val i = (value.invokeInstanceMethod(scope, "toList") as ObjList).list
                         list.addAll(i)
                     }
                     else -> scope.raiseClassCastError("expected list of objects for splat argument")
                 }
             } else {
                 list.add(value)
             }
         }
         return Arguments(list, tailBlockMode)
     }
 }
 
 data class Arguments(val list: List<Obj>, val tailBlockMode: Boolean = false) : List<Obj> by list {
 
     constructor(vararg values: Obj) : this(values.toList())
 
     fun firstAndOnly(pos: Pos = Pos.UNKNOWN): Obj {
         if (list.size != 1) throw ScriptError(pos, "expected one argument, got ${list.size}")
         val v = list.first()
         // Tiny micro-alloc win: avoid byValueCopy for immutable singletons
         return when (v) {
             net.sergeych.lyng.obj.ObjNull,
             net.sergeych.lyng.obj.ObjTrue,
             net.sergeych.lyng.obj.ObjFalse -> v
             else -> v.byValueCopy()
         }
     }
 
     /**
      * Convert to list of kotlin objects, see [Obj.toKotlin].
      */
     suspend fun toKotlinList(scope: Scope): List<Any?> {
         return list.map { it.toKotlin(scope) }
     }
 
     suspend fun inspect(scope: Scope): String = list.map{ it.inspect(scope)}.joinToString(",")
 
     companion object {
         val EMPTY = Arguments(emptyList())
         fun from(values: Collection<Obj>) = Arguments(values.toList())
     }
 }

