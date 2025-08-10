package net.sergeych.lyng

fun leftMargin(s: String): Int {
    var cnt = 0
    for (c in s) {
        when (c) {
            ' ' -> cnt++
            '\t' -> cnt = (cnt / 4.0 + 0.9).toInt() * 4
            else -> break
        }
    }
    return cnt
}
