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
        val limit = if (PerfFlags.ARG_SMALL_ARITY_12) 12 else 8
         var hasSplat = false
         var count = 0
         for (pa in this) {
             if (pa.isSplat) { hasSplat = true; break }
             count++
            if (count > limit) break
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
                 6 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5), tailBlockMode)
                 }
                 7 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6), tailBlockMode)
                 }
                 8 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7), tailBlockMode)
                 }
                9 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                    val a0 = this.elementAt(0).value.execute(scope)
                    val a1 = this.elementAt(1).value.execute(scope)
                    val a2 = this.elementAt(2).value.execute(scope)
                    val a3 = this.elementAt(3).value.execute(scope)
                    val a4 = this.elementAt(4).value.execute(scope)
                    val a5 = this.elementAt(5).value.execute(scope)
                    val a6 = this.elementAt(6).value.execute(scope)
                    val a7 = this.elementAt(7).value.execute(scope)
                    val a8 = this.elementAt(8).value.execute(scope)
                    Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8), tailBlockMode)
                } else null
                10 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                    val a0 = this.elementAt(0).value.execute(scope)
                    val a1 = this.elementAt(1).value.execute(scope)
                    val a2 = this.elementAt(2).value.execute(scope)
                    val a3 = this.elementAt(3).value.execute(scope)
                    val a4 = this.elementAt(4).value.execute(scope)
                    val a5 = this.elementAt(5).value.execute(scope)
                    val a6 = this.elementAt(6).value.execute(scope)
                    val a7 = this.elementAt(7).value.execute(scope)
                    val a8 = this.elementAt(8).value.execute(scope)
                    val a9 = this.elementAt(9).value.execute(scope)
                    Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9), tailBlockMode)
                } else null
                11 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                    val a0 = this.elementAt(0).value.execute(scope)
                    val a1 = this.elementAt(1).value.execute(scope)
                    val a2 = this.elementAt(2).value.execute(scope)
                    val a3 = this.elementAt(3).value.execute(scope)
                    val a4 = this.elementAt(4).value.execute(scope)
                    val a5 = this.elementAt(5).value.execute(scope)
                    val a6 = this.elementAt(6).value.execute(scope)
                    val a7 = this.elementAt(7).value.execute(scope)
                    val a8 = this.elementAt(8).value.execute(scope)
                    val a9 = this.elementAt(9).value.execute(scope)
                    val a10 = this.elementAt(10).value.execute(scope)
                    Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10), tailBlockMode)
                } else null
                12 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                    val a0 = this.elementAt(0).value.execute(scope)
                    val a1 = this.elementAt(1).value.execute(scope)
                    val a2 = this.elementAt(2).value.execute(scope)
                    val a3 = this.elementAt(3).value.execute(scope)
                    val a4 = this.elementAt(4).value.execute(scope)
                    val a5 = this.elementAt(5).value.execute(scope)
                    val a6 = this.elementAt(6).value.execute(scope)
                    val a7 = this.elementAt(7).value.execute(scope)
                    val a8 = this.elementAt(8).value.execute(scope)
                    val a9 = this.elementAt(9).value.execute(scope)
                    val a10 = this.elementAt(10).value.execute(scope)
                    val a11 = this.elementAt(11).value.execute(scope)
                    Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11), tailBlockMode)
                } else null
                 else -> null
             }
             if (quick != null) return quick
         }
     }
     // Single-splat fast path: if there is exactly one splat argument that evaluates to ObjList,
     // avoid builder and copies by returning its list directly.
     if (PerfFlags.ARG_BUILDER) {
         if (this.size == 1) {
             val only = this.first()
             if (only.isSplat) {
                 val v = only.value.execute(scope)
                 if (v is ObjList) {
                     return Arguments(v.list, tailBlockMode)
                 } else if (v.isInstanceOf(ObjIterable)) {
                     // Convert iterable to list once and return directly
                     val i = (v.invokeInstanceMethod(scope, "toList") as ObjList).list
                     return Arguments(i, tailBlockMode)
                 } else {
                     scope.raiseClassCastError("expected list of objects for splat argument")
                 }
             }
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
            net.sergeych.lyng.obj.ObjFalse,
            // Immutable scalars: safe to return directly
            is net.sergeych.lyng.obj.ObjInt,
            is net.sergeych.lyng.obj.ObjReal,
            is net.sergeych.lyng.obj.ObjChar,
            is net.sergeych.lyng.obj.ObjString -> v
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

