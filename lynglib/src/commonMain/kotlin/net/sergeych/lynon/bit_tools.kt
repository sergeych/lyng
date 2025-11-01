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

import kotlin.random.Random

/**
 * Hoq many tetrades needed to store the value. It is faster to use this function
 * than to use sizeInBits
 *
 * Size for 0 is 1
 */
fun sizeInTetrades(value: ULong): Int {
    if( value == 0UL ) return 1
    var size = 0
    var rest = value
    while( rest != 0UL ) {
        size++
        rest = rest shr 4
    }
    return size
}

/**
 * Calculates ow many bits needed to store the value. Size for 0, for example, is 1.
 */
@Suppress("unused")
fun sizeInBits(value: ULong): Int {
    if( value == 0UL ) return 1
    var size = 0
    var rest = value
    while( rest != 0UL ) {
        size++
        rest = rest shr 1
    }
    return size
}

fun sizeInBits(value: Int): Int = sizeInBits(value.toULong())

/**
 * Generates a random BitArray of the specified size. Important: this is
 * __not cryptographically secure__. Use random from [crypto2](https://gitea.sergeych.net/sergeych/crypto2),
 * `randomInt()`, `randomUBytes()`, etc., for cryptographically secure random data.
 *
 * @param sizeInBits The size of the BitArray to generate in bits.
 * @return A BitArray of the specified size filled with random bits.
 */
@Suppress("unused")
fun BitArray.Companion.random(sizeInBits: Int): BitArray {
    val result = BitArray.withBitSize(sizeInBits.toLong())
    for (i in 0..<sizeInBits) {
        result[i.toLong()] = Random.nextInt() and 1
    }
    return result
}