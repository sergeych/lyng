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

package net.sergeych.lynon

@Suppress("unused")
interface BitList {
    operator fun get(bitIndex: Long): Int
    operator fun set(bitIndex: Long,value: Int)
    val size: Long
    val indices: LongRange

    fun toInput(): BitInput = object : BitInput {
        private var index = 0L

        override fun getBitOrNull(): Int? =
            if( index < size) this@BitList[index++]
            else null
    }
}

fun bitListOf(vararg bits: Int): BitList {
    return if( bits.size > 64) {
        BitArray.ofBits(*bits)
    }
    else
        TinyBits.of(*bits)
}

@Suppress("unused")
fun bitListOfSize(sizeInBits: Long): BitList {
    return if( sizeInBits > 64) {
        BitArray.withBitSize(sizeInBits)
    }
    else
        TinyBits()
}