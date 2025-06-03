import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import net.sergeych.lyng.Context
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.test.Test

suspend fun executeSampleTests(fileName: String) {
    val sample = withContext(Dispatchers.IO) {
        Files.readString(Paths.get(fileName))
    }
    runBlocking {
        val c = Context()
        for( i in 1..1) {
            val start = Clock.System.now()
            c.eval(sample)
            val time = Clock.System.now() - start
            println("$time: $fileName")
//            delay(100)
        }
    }
}

class SamplesTest {

    @Test
    fun testSamples() = runBlocking {
        for (s in Files.list(Paths.get("../docs/samples"))) {
            if (s.extension == "lyng") executeSampleTests(s.toString())
        }
    }
}