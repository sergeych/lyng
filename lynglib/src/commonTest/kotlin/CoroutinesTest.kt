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

    @Test
    fun testFlows() = runTest {
        eval("""
            val f = flow {
                println("Starting generator")
                var n1 = 0
                var n2 = 1
                emit(n1)
                emit(n2)
                while(true) {
                    val n = n1 + n2
                    emit(n)
                    n1 = n2
                    n2 = n
                }
            }
            val correctFibs = [0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765]
            assertEquals( correctFibs, f.take(correctFibs.size))
            
        """.trimIndent())
    }

    @Test
    fun testFlow2() = runTest {
        eval("""
                val f = flow {
                    println("Starting generator")
                        emit("start")
                        emit("start2")
                        println("Emitting")
                        (1..4).forEach { 
//                            println("you hoo "+it)
                            emit(it) 
                        }
                        println("Done emitting")
                }
                // let's collect flow:
                val result = []
//                for( x in f ) result += x
                println(result)
            
                // let's collect it once again:
                println(f.toList())
                println(f.toList())
//                for( x in f ) println(x)
//                for( x in f ) println(x)
            
                //assertEquals( result, f.toList() )
        """.trimIndent())
    }
}