package net.sergeych.lyng.stdlib_included
internal val rootLyng = """
package lyng.stdlib
    
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
    i.next()
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
    
""".trimIndent()

