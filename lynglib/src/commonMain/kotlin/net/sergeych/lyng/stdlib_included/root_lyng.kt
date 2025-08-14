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

package net.sergeych.lyng.stdlib_included
internal val rootLyng = """
package lyng.stdlib
    
fun cached(builder) {
    var calculated = false
    var value = null
    {
        if( !calculated ) {
            value = builder()
            calculated = true
        }
        value
    }
}
    
fun Iterable.filter(predicate) {
    val list = this
    flow {
        for( item in list ) {
            if( predicate(item) ) {
                emit(item)
            }
        }
    }
}

fun Iterable.drop(n) {
    var cnt = 0
    filter { cnt++ >= n }
}

fun Iterable.first() {
    val i = iterator()
    if( !i.hasNext() ) throw NoSuchElementException()
    i.next().also { i.cancelIteration() }
}

fun Iterable.last() {
    var found = false
    var element = null
    for( i in this ) {
        element = i
        found = true
    }
    if( !found ) throw NoSuchElementException()
    element
}

fun Iterable.dropLast(n) {
    val list = this
    val buffer = RingBuffer(n)
    flow {
        for( item in list ) {
            if( buffer.size == n ) 
                emit( buffer.first() )
            buffer += item
        }
    }
}

fun Iterable.takeLast(n) {
    val list = this
    val buffer = RingBuffer(n)
    for( item in list ) buffer += item
    buffer
}

fun Iterable.joinToString(prefix=" ", transformer=null) {
    var result = null
    for( part in this ) {
        val transformed = transformer?(part)?.toString() ?: part.toString()
        if( result == null ) result = transformed
        else result += prefix + transformed
    }
    result ?: ""
}
    
""".trimIndent()

