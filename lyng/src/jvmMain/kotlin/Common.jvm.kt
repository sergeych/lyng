package net.sergeych

import kotlin.system.exitProcess

actual fun exit(code: Int) {
    exitProcess(code)
}