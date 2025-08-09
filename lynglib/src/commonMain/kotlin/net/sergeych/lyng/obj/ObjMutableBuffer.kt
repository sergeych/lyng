package net.sergeych.lyng.obj

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import net.sergeych.lyng.Scope

class ObjMutableBuffer(byteArray: UByteArray) : ObjBuffer(byteArray) {

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        byteArray[checkIndex(scope, index.toObj())] = when (newValue) {
            is ObjInt -> newValue.value.toUByte()
            is ObjChar -> newValue.value.code.toUByte()
            else -> scope.raiseIllegalArgument(
                "invalid byte value for buffer at index ${index.inspect()}: ${newValue.inspect()}"
            )
        }
    }

    companion object {

        private suspend fun createBufferFrom(scope: Scope, obj: Obj): ObjBuffer =
            when (obj) {
                is ObjBuffer -> ObjMutableBuffer(obj.byteArray.copyOf())
                is ObjInt -> {
                    if (obj.value < 0)
                        scope.raiseIllegalArgument("buffer size must be positive")
                    val data = UByteArray(obj.value.toInt())
                    ObjMutableBuffer(data)
                }

                is ObjString -> ObjMutableBuffer(obj.value.encodeToByteArray().asUByteArray())
                else -> {
                    if (obj.isInstanceOf(ObjIterable)) {
                        ObjMutableBuffer(
                            obj.toFlow(scope).map { it.toLong().toUByte() }.toList().toTypedArray()
                                .toUByteArray()
                        )
                    } else
                        scope.raiseIllegalArgument(
                            "can't construct buffer from ${obj.inspect()}"
                        )
                }
            }

        val type = object : ObjClass("MutableBuffer", ObjBuffer.type) {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args.list
                return when (args.size) {
                    // empty buffer
                    0 -> ObjMutableBuffer(ubyteArrayOf())
                    1 -> createBufferFrom(scope, args[0])
                    else -> {
                        // create buffer from array, each argument should be a byte then:
                        val data = UByteArray(args.size)
                        for ((i, b) in args.withIndex()) {
                            val code = when (b) {
                                is ObjChar -> b.value.code.toUByte()
                                is ObjInt -> b.value.toUByte()
                                else -> scope.raiseIllegalArgument(
                                    "invalid byte value for buffer constructor at index $i: ${b.inspect()}"
                                )
                            }
                            data[i] = code
                        }
                        ObjMutableBuffer(data)
                    }
                }
            }
        }
    }
}
