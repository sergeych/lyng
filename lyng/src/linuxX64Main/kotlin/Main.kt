import com.github.ajalt.clikt.core.main
import kotlinx.coroutines.runBlocking
import net.sergeych.Lyng

fun main(args: Array<String>) {
    Lyng( { runBlocking { it() } }).main(args)
}