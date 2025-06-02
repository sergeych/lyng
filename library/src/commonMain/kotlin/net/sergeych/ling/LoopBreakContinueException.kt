package net.sergeych.lying

class LoopBreakContinueException(
    val doContinue: Boolean,
    val result: Obj = ObjVoid,
    val label: String? = null
) : RuntimeException()