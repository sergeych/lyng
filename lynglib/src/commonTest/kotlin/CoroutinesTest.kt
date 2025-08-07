import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class TestCoroutines {

    @Test
    fun testLaunch() = runTest {
        eval(
            """
            var passed = false
            val x = launch { 
                delay(10)
                passed = true
                "ok"
            }
            assert(!passed)
            assertEquals( x.await(), "ok")
            assert(passed)
            assert(x.isCompleted)
        """.trimIndent()
        )
    }

    @Test
    fun testCompletableDeferred() = runTest {
        eval(
            """
            val done = CompletableDeferred()
            
            launch { 
                delay(10)
                done.complete("ok")
            }
            
            assert(!done.isCompleted)
            assert(done.isActive)
            assertEquals( done.await(), "ok")
            assert(done.isCompleted)
        """.trimIndent()
        )
    }

    @Test
    fun testMutex() = runTest {
        eval(
            """
            var counter = 0
            val mutex = Mutex()
            
            (1..4).map { 
                launch {
//                    mutex.withLock {
                        val c = counter
                        delay(5)
                        counter = c + 1
//                    }
                }
             }.forEach { it.await() }
             println(counter)
             assert( counter < 10 )
             
    """.trimIndent()
        )
    }
}