package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

class LoopBreakContinueException(
    val doContinue: Boolean,
    val result: Obj = ObjVoid,
    val label: String? = null
) : RuntimeException()