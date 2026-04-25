/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class DeclAnnotationIntrospectionTest {

    @Test
    fun classAnnotationQueriesExposeConstructorAndMemberAnnotations() = runTest {
        val scope = Scope()
        val result = scope.eval(
            """
            val suffix = "!"
            
            object Marker
            
            class Sample(
                @Transient @Tag(1, label: "ctor", extra: suffix) val x: Int
            ) {
                @Transient @DbDecodeWith(Marker)
                var y: Int = 10
            }
            
            val ctorAnnotations: ImmutableList<Map<String, Object>> = Sample.getConstructorAnnotations("x")
            val ctorTag: Map<String, Object> = ctorAnnotations[1]
            val ctorPositional: ImmutableList<Object> = ctorTag["positional"] as ImmutableList<Object>
            val ctorNamed: Map<String, Object> = ctorTag["named"] as Map<String, Object>
            assertEquals(2, ctorAnnotations.size)
            assertEquals("Transient", ctorAnnotations[0]["name"])
            assertEquals("Tag", ctorTag["name"])
            assertEquals(1, ctorPositional[0])
            assertEquals("ctor", ctorNamed["label"])
            assertEquals("!", ctorNamed["extra"])
            
            val memberAnnotations: ImmutableList<Map<String, Object>> = Sample.getMemberAnnotations("y")
            val memberDecodeWith: Map<String, Object> = memberAnnotations[1]
            val memberPositional: ImmutableList<Object> = memberDecodeWith["positional"] as ImmutableList<Object>
            assertEquals(2, memberAnnotations.size)
            assertEquals("Transient", memberAnnotations[0]["name"])
            assertEquals("DbDecodeWith", memberDecodeWith["name"])
            assertEquals(Marker, memberPositional[0])
            
            memberAnnotations.size + ctorAnnotations.size
            """.trimIndent()
        ) as ObjInt

        assertEquals(4L, result.value)
    }
}
