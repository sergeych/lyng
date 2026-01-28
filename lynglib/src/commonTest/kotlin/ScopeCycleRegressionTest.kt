/*
 * Regression test for scope parent-chain cycle when reusing pooled frames
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("TODO(bytecode-only): uses fallback")
class ScopeCycleRegressionTest {
    @Test
    fun instanceMethodCallDoesNotCycle() = runTest {
        eval(
            (
                """
                class Whatever {
                    fun something() {
                        println
                    }
                }

                fun ll() { Whatever() }

                fun callTest1() {
                    val l = ll()
                    l.something()
                    "ok"
                }

                assertEquals("ok", callTest1())
                """
                ).trimIndent()
        )
    }
}
