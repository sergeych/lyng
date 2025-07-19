package net.sergeych.lyng.obj

import net.sergeych.bintools.toDump
import net.sergeych.lyng.Scope
import net.sergeych.lynon.BitArray

class ObjBitBuffer(val bitArray: BitArray) : Obj() {

    override val objClass = type

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return bitArray[index.toLong()].toObj()
    }

    companion object {
        val type = object: ObjClass("BitBuffer", ObjArray) {

        }.apply {
            addFn("toBuffer") {
                requireNoArgs()
                ObjBuffer(thisAs<ObjBitBuffer>().bitArray.asUbyteArray())
            }
            addFn("toDump") {
                requireNoArgs()
                ObjString(
                    thisAs<ObjBitBuffer>().bitArray.asUbyteArray().toDump()
                )
            }
            addFn("size") {
                thisAs<ObjBitBuffer>().bitArray.size.toObj()
            }
            addFn("sizeInBytes") {
                ObjInt((thisAs<ObjBitBuffer>().bitArray.size + 7) shr 3)
            }
        }
    }
}