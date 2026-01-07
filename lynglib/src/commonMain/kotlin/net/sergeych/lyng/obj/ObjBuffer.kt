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

package net.sergeych.lyng.obj

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import net.sergeych.bintools.decodeHex
import net.sergeych.bintools.encodeToHex
import net.sergeych.bintools.toDump
import net.sergeych.lyng.Scope
import net.sergeych.lyng.statement
import net.sergeych.lynon.BitArray
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import net.sergeych.mp_tools.decodeBase64Url
import net.sergeych.mp_tools.encodeToBase64Url
import kotlin.math.min

open class ObjBuffer(val byteArray: UByteArray) : Obj() {

    override val objClass: ObjClass get() = type

    val hex by lazy { byteArray.encodeToHex("")}
    val base64 by lazy { byteArray.toByteArray().encodeToBase64Url()}

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

    val size by byteArray::size

    override fun hashCode(): Int {
        // On some platforms (notably JS), UByteArray.hashCode() is not content-based.
        // For map/set keys we must ensure hash is consistent with equals(contentEquals).
        // Convert to ByteArray and use contentHashCode() which is value-based and stable.
        return byteArray.asByteArray().contentHashCode()
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
        } else scope.raiseIllegalArgument("can't concatenate buffer with ${other.inspect(scope)}")
    }

    override fun toString(): String = base64

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ObjBuffer

        return byteArray contentEquals other.byteArray
    }

    override suspend fun lynonType(): LynonType = LynonType.Buffer

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        encoder.encodeCachedBytes(byteArray.asByteArray())
    }

    override suspend fun inspect(scope: Scope): String = "Buf($base64)"

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
                            "can't construct buffer from ${obj.inspect(scope)}"
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
                                    "invalid byte value for buffer constructor at index $i: ${b.inspect(scope)}"
                                )
                            }
                            data[i] = code
                        }
                        ObjBuffer(data)
                    }
                }
            }

            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
                ObjBuffer( decoder.decodeCached {
                    decoder.decompress().asUByteArray()
                })

        }.apply {
            addClassFn("decodeBase64") {
                ObjBuffer(requireOnlyArg<Obj>().toString().decodeBase64Url().asUByteArray())
            }
            addClassFn("decodeHex") {
                ObjBuffer(requireOnlyArg<Obj>().toString().decodeHex().asUByteArray())
            }
            createField("size",
                statement {
                    (thisObj as ObjBuffer).byteArray.size.toObj()
                }
            )
            createField("hex",
                statement {
                    thisAs<ObjBuffer>().hex.toObj()
                }
            )
            createField("base64",
                statement {
                    thisAs<ObjBuffer>().base64.toObj()
                }
            )
            addFn("decodeUtf8") {
                ObjString(
                    thisAs<ObjBuffer>().byteArray.toByteArray().decodeToString()
                )
            }
            addFn("toMutable") {
                requireNoArgs()
                ObjMutableBuffer(thisAs<ObjBuffer>().byteArray.copyOf())
            }
            addFn("toDump") {
                requireNoArgs()
                ObjString(
                    thisAs<ObjBuffer>().byteArray.toByteArray().toDump()
                )
            }
            addFn("toBitInput") {
                ObjBitBuffer(BitArray(thisAs<ObjBuffer>().byteArray, 8))
            }
        }
    }
}