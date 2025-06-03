package net.sergeych.lyng_cli

import com.github.ajalt.clikt.core.main
import kotlinx.coroutines.runBlocking
import net.sergeych.LyngCLI

fun main(args: Array<String>) {
    LyngCLI({ runBlocking { it() } }).main(args)
}