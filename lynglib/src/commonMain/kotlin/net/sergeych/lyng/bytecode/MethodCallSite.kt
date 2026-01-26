/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.PerfStats
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Visibility
import net.sergeych.lyng.canAccessMember
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjIllegalAccessException
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjRecord

class MethodCallSite(private val name: String) {
    private var mKey1: Long = 0L; private var mVer1: Int = -1
    private var mInvoker1: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey2: Long = 0L; private var mVer2: Int = -1
    private var mInvoker2: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey3: Long = 0L; private var mVer3: Int = -1
    private var mInvoker3: (suspend (Obj, Scope, Arguments) -> Obj)? = null
    private var mKey4: Long = 0L; private var mVer4: Int = -1
    private var mInvoker4: (suspend (Obj, Scope, Arguments) -> Obj)? = null

    private var mAccesses: Int = 0; private var mMisses: Int = 0; private var mPromotedTo4: Boolean = false
    private var mFreezeWindowsLeft: Int = 0
    private var mWindowAccesses: Int = 0
    private var mWindowMisses: Int = 0

    private inline fun size4MethodsEnabled(): Boolean =
        PerfFlags.METHOD_PIC_SIZE_4 ||
            ((PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY) && mPromotedTo4 && mFreezeWindowsLeft == 0)

    private fun noteMethodHit() {
        if (!(PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY)) return
        val a = (mAccesses + 1).coerceAtMost(1_000_000)
        mAccesses = a
        if (PerfFlags.PIC_ADAPTIVE_HEURISTIC) {
            mWindowAccesses = (mWindowAccesses + 1).coerceAtMost(1_000_000)
            if (mWindowAccesses >= 256) endHeuristicWindow()
        }
    }

    private fun noteMethodMiss() {
        if (!(PerfFlags.PIC_ADAPTIVE_2_TO_4 || PerfFlags.PIC_ADAPTIVE_METHODS_ONLY)) return
        val a = (mAccesses + 1).coerceAtMost(1_000_000)
        mAccesses = a
        mMisses = (mMisses + 1).coerceAtMost(1_000_000)
        if (!mPromotedTo4 && mFreezeWindowsLeft == 0 && a >= 256) {
            if (mMisses * 100 / a > 20) mPromotedTo4 = true
            mAccesses = 0; mMisses = 0
        }
        if (PerfFlags.PIC_ADAPTIVE_HEURISTIC) {
            mWindowAccesses = (mWindowAccesses + 1).coerceAtMost(1_000_000)
            mWindowMisses = (mWindowMisses + 1).coerceAtMost(1_000_000)
            if (mWindowAccesses >= 256) endHeuristicWindow()
        }
    }

    private fun endHeuristicWindow() {
        val accesses = mWindowAccesses
        val misses = mWindowMisses
        mWindowAccesses = 0
        mWindowMisses = 0
        if (mFreezeWindowsLeft > 0) {
            mFreezeWindowsLeft = (mFreezeWindowsLeft - 1).coerceAtLeast(0)
            return
        }
        if (mPromotedTo4 && accesses >= 256) {
            val rate = misses * 100 / accesses
            if (rate >= 25) {
                mPromotedTo4 = false
                mFreezeWindowsLeft = 4
            }
        }
    }

    suspend fun invoke(scope: Scope, base: Obj, callArgs: Arguments): Obj {
        if (PerfFlags.METHOD_PIC) {
            val (key, ver) = when (base) {
                is ObjInstance -> base.objClass.classId to base.objClass.layoutVersion
                is ObjClass -> base.classId to base.layoutVersion
                else -> 0L to -1
            }
            if (key != 0L) {
                mInvoker1?.let { inv ->
                    if (key == mKey1 && ver == mVer1) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.methodPicHit++
                        noteMethodHit()
                        return inv(base, scope, callArgs)
                    }
                }
                mInvoker2?.let { inv ->
                    if (key == mKey2 && ver == mVer2) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.methodPicHit++
                        noteMethodHit()
                        val tK = mKey2; val tV = mVer2; val tI = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                if (size4MethodsEnabled()) mInvoker3?.let { inv ->
                    if (key == mKey3 && ver == mVer3) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.methodPicHit++
                        noteMethodHit()
                        val tK = mKey3; val tV = mVer3; val tI = mInvoker3
                        mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                if (size4MethodsEnabled()) mInvoker4?.let { inv ->
                    if (key == mKey4 && ver == mVer4) {
                        if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.methodPicHit++
                        noteMethodHit()
                        val tK = mKey4; val tV = mVer4; val tI = mInvoker4
                        mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                        mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                        mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                        mKey1 = tK; mVer1 = tV; mInvoker1 = tI
                        return inv(base, scope, callArgs)
                    }
                }
                if (PerfFlags.PIC_DEBUG_COUNTERS) PerfStats.methodPicMiss++
                noteMethodMiss()
                val result = try {
                    base.invokeInstanceMethod(scope, name, callArgs)
                } catch (e: ExecutionError) {
                    mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                    mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                    mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                    mKey1 = key; mVer1 = ver; mInvoker1 = { _, sc, _ ->
                        sc.raiseError(e.message ?: "method not found: $name")
                    }
                    throw e
                }
                if (size4MethodsEnabled()) {
                    mKey4 = mKey3; mVer4 = mVer3; mInvoker4 = mInvoker3
                    mKey3 = mKey2; mVer3 = mVer2; mInvoker3 = mInvoker2
                }
                mKey2 = mKey1; mVer2 = mVer1; mInvoker2 = mInvoker1
                when (base) {
                    is ObjInstance -> {
                        val cls0 = base.objClass
                        val keyInScope = cls0.publicMemberResolution[name]
                        val methodSlot = if (keyInScope != null) cls0.methodSlotForKey(keyInScope) else null
                        val fastRec = if (methodSlot != null) {
                            val idx = methodSlot.slot
                            if (idx >= 0 && idx < base.methodSlots.size) base.methodSlots[idx] else null
                        } else if (keyInScope != null) {
                            base.methodRecordForKey(keyInScope) ?: base.instanceScope.objects[keyInScope]
                        } else null
                        val resolved = if (fastRec != null) null else cls0.resolveInstanceMember(name)
                        val targetRec = when {
                            fastRec != null && fastRec.type == ObjRecord.Type.Fun -> fastRec
                            resolved != null && resolved.record.type == ObjRecord.Type.Fun && !resolved.record.isAbstract -> resolved.record
                            else -> null
                        }
                        if (targetRec != null) {
                            val visibility = targetRec.visibility
                            val decl = targetRec.declaringClass ?: (resolved?.declaringClass ?: cls0)
                            if (methodSlot != null && targetRec.type == ObjRecord.Type.Fun) {
                                val slotIndex = methodSlot.slot
                                mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                    val inst = obj as ObjInstance
                                    if (inst.objClass === cls0) {
                                        val rec = if (slotIndex >= 0 && slotIndex < inst.methodSlots.size) inst.methodSlots[slotIndex] else null
                                        if (rec != null && rec.type == ObjRecord.Type.Fun && !rec.isAbstract) {
                                            if (!visibility.isPublic && !canAccessMember(visibility, decl, sc.currentClassCtx, name)) {
                                                sc.raiseError(ObjIllegalAccessException(sc, "can't invoke non-public method $name"))
                                            }
                                            rec.value.invoke(inst.instanceScope, inst, a, decl)
                                        } else {
                                            obj.invokeInstanceMethod(sc, name, a)
                                        }
                                    } else {
                                        obj.invokeInstanceMethod(sc, name, a)
                                    }
                                }
                            } else {
                                val callable = targetRec.value
                                mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                    val inst = obj as ObjInstance
                                    if (!visibility.isPublic && !canAccessMember(visibility, decl, sc.currentClassCtx, name)) {
                                        sc.raiseError(ObjIllegalAccessException(sc, "can't invoke non-public method $name"))
                                    }
                                    callable.invoke(inst.instanceScope, inst, a)
                                }
                            }
                        } else {
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                obj.invokeInstanceMethod(sc, name, a)
                            }
                        }
                    }
                    is ObjClass -> {
                        val clsScope = base.classScope
                        val rec = clsScope?.get(name)
                        if (rec != null) {
                            val callable = rec.value
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                callable.invoke(sc, obj, a)
                            }
                        } else {
                            mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                                obj.invokeInstanceMethod(sc, name, a)
                            }
                        }
                    }
                    else -> {
                        mKey1 = key; mVer1 = ver; mInvoker1 = { obj, sc, a ->
                            obj.invokeInstanceMethod(sc, name, a)
                        }
                    }
                }
                return result
            }
        }
        return base.invokeInstanceMethod(scope, name, callArgs)
    }
}
