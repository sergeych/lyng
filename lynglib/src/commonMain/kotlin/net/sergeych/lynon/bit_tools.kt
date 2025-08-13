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
 * How many bits needed to store the value. Size for 0 is 1,
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