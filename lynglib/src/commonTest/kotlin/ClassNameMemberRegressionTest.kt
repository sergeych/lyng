import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClassNameMemberRegressionTest {
    @Test
    fun rejectsClassNamedMembersAtTheirDeclaration() = runTest {
        for (member in listOf(
            "fun Same(y): String = y.toString()",
            "fun Same(y, z): String = y.toString() + z.toString()",
            "fun Same(y): Same = Same(y * 2)",
            "static fun Same(y): Same = Same(y)",
            "fun Same(y): String = \"member\"; fun literal() = Same::class.toString()"
        )) {
            val error = assertFailsWith<ScriptError> {
                eval("class Same(x) {\n    $member\n}")
            }
            assertContains(error.errorMessage, "member function cannot have the same name as its enclosing class")
            assertEquals(1, error.pos.line)
        }
    }

    @Test
    fun thisIsNotConstructorDelegation() = runTest {
        for (receiver in listOf("this", "this@Factory")) {
            val error = assertFailsWith<ScriptError> {
                eval("class Factory(x) { fun again(): Factory = $receiver(x + 1) }")
            }
            assertContains(error.errorMessage, "constructor delegation through this is not supported")
        }
    }

    @Test
    fun constructorAndClassLiteralRemainAvailable() = runTest {
        assertEquals("3", eval("""
            class Factory(val x: Int) {
                fun again(y: Int): Factory = Factory(y)
                fun literal(): String = Factory::class.toString()
            }
            val f = Factory(1)
            f.literal()
            f.again(3).x.toString()
        """.trimIndent()).toString())
    }

    @Test
    fun localFunctionsCanStillShadowClassNames() = runTest {
        assertEquals("local 7", eval("""
            class Local(x) {
                fun probe(y): String {
                    fun Local(z): String = "local " + z.toString()
                    return Local(y)
                }
            }
            Local(1).probe(7)
        """.trimIndent()).toString())
    }
}
