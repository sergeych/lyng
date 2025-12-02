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
            if( predicate(item) ) {ln
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
    val buffer = RingBuffer(n)
    for( item in this ) buffer += item
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

fun Iterable.any(predicate): Bool {
    for( i in this ) {
        if( predicate(i) )
            break true
    } else false
}

fun Iterable.all(predicate): Bool {
    !any { !predicate(it) }
}

fun Iterable.sum() {
    val i = iterator()
    if( i.hasNext() ) {
        var result = i.next()
        while( i.hasNext() ) result += i.next()
        result
    }
    else null
}

fun Iterable.sumOf(f) {
    val i = iterator()
    if( i.hasNext() ) {
        var result = f(i.next())
        while( i.hasNext() ) result += f(i.next())
        result
    }
    else null
}

fun Iterable.minOf( lambda ) {
    val i = iterator()
    var minimum = lambda( i.next() )
    while( i.hasNext() ) {
        val x = lambda(i.next())
        if( x < minimum ) minimum = x
    }
    minimum
}

/*
    Return maximum value of the given function applied to elements of the collection.
*/    
fun Iterable.maxOf( lambda ) {
    val i = iterator()
    var maximum = lambda( i.next() )
    while( i.hasNext() ) {
        val x = lambda(i.next())
        if( x > maximum ) maximum = x
    }
    maximum
}

fun Iterable.sorted() {
    sortedWith { a, b -> a <=> b }
}

fun Iterable.sortedBy(predicate) {
    sortedWith { a, b -> predicate(a) <=> predicate(b) }
}

fun Iterable.shuffled() {
    toList().apply { shuffle() }
}

fun List.toString() {
    "[" + joinToString(",") + "]"
}

fun List.sortBy(predicate) {
    sortWith { a, b -> predicate(a) <=> predicate(b) }
}

fun List.sort() {
    sortWith { a, b -> a <=> b }
}

class StackTraceEntry(
    val sourceName: String,
    val line: Int,
    val column: Int,
    val sourceString: String
) {
    fun toString() {
        "%s:%d:%d: %s"(sourceName, line, column, sourceString.trim())
    }
}

fun Exception.printStackTrace() {
    println(this)
    for( entry in stackTrace() ) {
        println("\tat "+entry)
    }
}

fun String.re() { Regex(this) }

    
""".trimIndent()

