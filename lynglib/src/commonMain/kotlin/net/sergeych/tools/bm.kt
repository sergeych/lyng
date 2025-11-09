package net.sergeych.tools

import kotlinx.datetime.Clock

inline fun bm(text: String="", f: ()->Unit) {
    val start = Clock.System.now()
    f()
    println("$text: ${Clock.System.now() - start}")
}