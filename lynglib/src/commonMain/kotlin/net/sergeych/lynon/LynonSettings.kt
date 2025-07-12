package net.sergeych.lynon

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjInt

open class LynonSettings() {

    open fun shouldCache(obj: Obj): Boolean = when (obj) {
        is ObjChar -> false
        is ObjInt -> obj.value > 0x10000FF
        is ObjBool -> false
        else -> true
    }

    companion object {
        val default = LynonSettings()
    }
}