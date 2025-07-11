package net.sergeych.lyng.obj

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import net.sergeych.lyng.Scope
import net.sergeych.lyng.statement
import kotlin.math.min

class ObjBuffer(val byteArray: UByteArray) : Obj() {

    override val objClass: ObjClass = type

    fun checkIndex(scope: Scope, index: Obj): Int {
        if (index !is ObjInt)
            scope.raiseIllegalArgument("index must be Int")
        val i = index.value.toInt()
        if (i < 0) scope.raiseIllegalArgument("index must be positive")
        if (i >= byteArray.size)
            scope.raiseIndexOutOfBounds("index $i is out of bounds 0..<${byteArray.size}")
        return i
    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        // notice: we create a copy if content, so we don't want it
        // to be treated as modifiable, or putAt will not be called:
        return if (index is ObjRange) {
            val start: Int = index.startInt(scope)
            val end: Int = index.exclusiveIntEnd(scope) ?: size
            ObjBuffer(byteArray.sliceArray(start..<end))
        } else ObjInt(byteArray[checkIndex(scope, index)].toLong(), true)
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        byteArray[checkIndex(scope, index.toObj())] = when (newValue) {
            is ObjInt -> newValue.value.toUByte()
            is ObjChar -> newValue.value.code.toUByte()
            else -> scope.raiseIllegalArgument(
                "invalid byte value for buffer at index ${index.inspect()}: ${newValue.inspect()}"
            )
        }
    }

    val size by byteArray::size

    override fun hashCode(): Int {
        return byteArray.hashCode()
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other !is ObjBuffer) return super.compareTo(scope, other)
        val limit = min(size, other.size)
        for (i in 0..<limit) {
            val own = byteArray[i]
            val their = other.byteArray[i]
            if (own < their) return -1
            else if (own > their) return 1
        }
        if (size < other.size) return -1
        if (size > other.size) return 1
        return 0
    }

    override suspend fun plus(scope: Scope, other: Obj): Obj {
        return if (other is ObjBuffer)
            ObjBuffer(byteArray + other.byteArray)
        else if (other.isInstanceOf(ObjIterable)) {
            ObjBuffer(
                byteArray + other.toFlow(scope).map { it.toLong().toUByte() }.toList().toTypedArray()
                    .toUByteArray()
            )
        } else scope.raiseIllegalArgument("can't concatenate buffer with ${other.inspect()}")
    }

    override fun toString(): String {
        return "Buffer(${byteArray.toList()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjBuffer

        return byteArray contentEquals other.byteArray
    }

    companion object {
        private suspend fun createBufferFrom(scope: Scope, obj: Obj): ObjBuffer =
            when (obj) {
                is ObjBuffer -> ObjBuffer(obj.byteArray.copyOf())
                is ObjInt -> {
                    if (obj.value < 0)
                        scope.raiseIllegalArgument("buffer size must be positive")
                    val data = UByteArray(obj.value.toInt())
                    ObjBuffer(data)
                }

                is ObjString -> ObjBuffer(obj.value.encodeToByteArray().asUByteArray())
                else -> {
                    if (obj.isInstanceOf(ObjIterable)) {
                        ObjBuffer(
                            obj.toFlow(scope).map { it.toLong().toUByte() }.toList().toTypedArray()
                                .toUByteArray()
                        )
                    } else
                        scope.raiseIllegalArgument(
                            "can't construct buffer from ${obj.inspect()}"
                        )
                }
            }

        val type = object : ObjClass("Buffer", ObjArray) {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args.list
                return when (args.size) {
                    // empty buffer
                    0 -> ObjBuffer(ubyteArrayOf())
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
                        ObjBuffer(data)
                    }
                }
            }
        }.apply {
            createField("size",
                statement {
                    (thisObj as ObjBuffer).byteArray.size.toObj()
                }
            )
            addFn("decodeUtf8") {
                ObjString(
                    thisAs<ObjBuffer>().byteArray.toByteArray().decodeToString()
                )
            }
//            )
//            addFn("getAt") {
//                requireExactCount(1)
//                thisAs<ObjList>().getAt(this, requiredArg<Obj>(0))
//            }
//            addFn("putAt") {
//                requireExactCount(2)
//                val newValue = args[1]
//                thisAs<ObjList>().putAt(this, requiredArg<ObjInt>(0).value.toInt(), newValue)
//                newValue
//            }

        }
    }
}