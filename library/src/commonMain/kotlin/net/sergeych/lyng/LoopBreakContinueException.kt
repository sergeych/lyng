package net.sergeych.lyng

class LoopBreakContinueException(
    val doContinue: Boolean,
    val result: Obj = ObjVoid,
    val label: String? = null
) : RuntimeException()