package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test

class PropsTest {

    @Test
    fun propsProposal() = runTest {
        eval("""
            
            class WithProps {
 
                // readonly property without declared type:
                val readonlyProp 
                    get() {
                        "readonly foo"
                    }
 
                val readonlyWithType: Int get() { 42 }
                
                private var field = 0
                private var field2 = ""
            
                // with type declaration
                var propName: Int 
                    get() { 
                        field * 10 
                    }
                    set(value) { 
                        field = value 
                    }
                
                // or without
                var propNameWithoutType 
                    get() { 
                        "/"+ field2 + "/" 
                    }
                    set(value) { 
                        field2 = value 
                    }
            }
            
            val w = WithProps()
            assertEquals("readonly foo", w.readonlyProp)
            assertEquals(42, w.readonlyWithType)
            
            w.propNameWithoutType = "foo"
            assertEquals("/foo/", w.propNameWithoutType)
            
            w.propName = 123
            assertEquals(1230, w.propName)

            class Shorthand {
                private var _p = 0
                var p: Int 
                    get() = _p * 2 
                    set(v) = _p = v
            }
            val s = Shorthand()
            s.p = 21
            assertEquals(42, s.p)
            
        """.trimIndent())
    }
}
