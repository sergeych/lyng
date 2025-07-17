package net.sergeych.lynon

import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import kotlin.math.absoluteValue

open class LynonSettings() {

    open fun shouldCache(obj: Any): Boolean = when (obj) {
        is ObjChar -> false
        is ObjInt -> obj.value.absoluteValue > 0x10000FF
        is ObjBool -> false
        is ObjNull -> false
        is ByteArray -> obj.size > 2
        is UByteArray -> obj.size > 2
        else -> true
    }

    companion object {
        val default = LynonSettings()
    }
}