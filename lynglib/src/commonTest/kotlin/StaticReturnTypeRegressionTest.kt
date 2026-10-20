import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticReturnTypeRegressionTest {
    @Test
    fun declaredFactoryReturnSupportsInferredVariablesAndChains() = runTest {
        assertEquals("hi hi hi", eval("""
            class Factory(x) {
                static fun make(y): Factory = Factory(y)
                fun hi(): String = "hi"
            }
            val a: Factory = Factory.make(1)
            val b = Factory.make(2)
            a.hi() + " " + b.hi() + " " + Factory.make(3).hi()
        """.trimIndent()).toString())
    }

    @Test
    fun declaredScalarReturnsSupportMembersAndArithmetic() = runTest {
        assertEquals("4", eval("""
            class Scalars {
                static fun text(y): String = "abc"
                static fun num(y: Int): Int = y
            }
            val s = Scalars.text(1)
            val n = Scalars.num(1)
            s.length + n
        """.trimIndent()).toString())
    }

    @Test
    fun singletonStaticReturnSupportsMembers() = runTest {
        assertEquals("3", eval("""
            object Singleton { static fun text(): String = "abc" }
            val text = Singleton.text()
            text.length
        """.trimIndent()).toString())
    }

    @Test
    fun singletonStaticTailBlockAndInstanceMethodsUseTheirOwnDispatch() = runTest {
        assertEquals("6", eval("""
            object Singleton {
                static fun text(block: ()->String): String = block()
                fun instanceText(): String = "abc"
            }
            Singleton.text { "abc" }.length + Singleton.instanceText().length
        """.trimIndent()).toString())
    }

    @Test
    fun inferredStaticReturnMatchesInstanceAndTopLevelFunctions() = runTest {
        assertEquals("9", eval("""
            class Factory {
                static fun text() = "abc"
                fun instanceText() = "abc"
            }
            fun topText() = "abc"
            Factory.text().length + Factory().instanceText().length + topText().length
        """.trimIndent()).toString())
    }

    @Test
    fun genericStaticReturnSubstitutesArgumentType() = runTest {
        assertEquals("7", eval("""
            class Box<T>(val value: T) {
                static fun of<T>(v: T): Box<T> = Box(v)
            }
            val box = Box.of("abc")
            val explicit = Box.of<String>("abcd")
            box.value.length + explicit.value.length
        """.trimIndent()).toString())
    }
}
