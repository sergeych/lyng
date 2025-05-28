package net.sergeych.ling

class IfScope(val isTrue: Boolean) {

    fun otherwise(f: ()->Unit): Boolean {
        if( !isTrue ) f()
        return false
    }
}