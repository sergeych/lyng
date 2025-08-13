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


class MemoryBitInput(val packedBits: UByteArray, val lastByteBits: Int) : BitInput {

    constructor(ba: BitArray) : this(ba.bytes, ba.lastByteBits) {}
    constructor(mba: MemoryBitOutput) : this(mba.toBitArray()) {}

    private var index = 0

    private var isEndOfStream: Boolean = packedBits.isEmpty() || (packedBits.size == 1 && lastByteBits == 0)
        private set

    /**
     * Return next byte, int in 0..255 range, or -1 if end of stream reached
     */
    private var accumulator = if( isEndOfStream ) 0 else packedBits[0].toInt()

    private var bitCounter = 0

    override fun getBitOrNull(): Int? {
        if (isEndOfStream) return null
        val result = accumulator and 1
        accumulator = accumulator shr 1
        bitCounter++
        // is end?
        if( index == packedBits.lastIndex && bitCounter == lastByteBits ) {
            isEndOfStream = true
        }
        else {
            if( bitCounter == 8 ) {
                bitCounter = 0
                accumulator = packedBits[++index].toInt()
            }
        }
        return result
    }


}