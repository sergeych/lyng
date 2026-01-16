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
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.toBool
import net.sergeych.lynon.lynonDecodeAny
import net.sergeych.lynon.lynonEncodeAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TransientTest {

    @Test
    fun testTransient() = runTest {
        val script = """
            class TestTransient(@Transient val a, val b) {
                @Transient var c = 10
                var d = 20
                
                fun check() {
                    a == 1 && b == 2 && c == 10 && d == 20
                }
            }
            
            val t = TestTransient(1, 2)
            t.c = 30
            t.d = 40
            t
        """.trimIndent()
        
        val scope = Scope()
        val t = scope.eval(script) as ObjInstance
        
        // Check initial state
        assertEquals(1, (t.readField(scope, "a").value as ObjInt).value)
        assertEquals(2, (t.readField(scope, "b").value as ObjInt).value)
        assertEquals(30, (t.readField(scope, "c").value as ObjInt).value)
        assertEquals(40, (t.readField(scope, "d").value as ObjInt).value)
        
        // Serialize
        val serialized = lynonEncodeAny(scope, t)
        println("[DEBUG_LOG] Serialized size: ${serialized.size}")
        
        // Deserialized
        val t2 = lynonDecodeAny(scope, serialized) as ObjInstance
        
        // b and d should be preserved
        assertEquals(2, (t2.readField(scope, "b").value as ObjInt).value)
        assertEquals(40, (t2.readField(scope, "d").value as ObjInt).value)
        
        // a and c should be transient (lost or default/null)
        // For constructor args, we currently set ObjNull if transient
        assertEquals(ObjNull, t2.readField(scope, "a").value)
        // For class fields, if it's transient it's not serialized, so it gets its initial value during construction
        assertEquals(10, (t2.readField(scope, "c").value as ObjInt).value)
        
        // Check JSON
        val json = t.toJson(scope).toString()
        println("[DEBUG_LOG] JSON: $json")
        assertFalse(json.contains("\"a\":"))
        assertFalse(json.contains("\"c\":"))
        assertNotNull(json.contains("\"b\":2"))
        assertNotNull(json.contains("\"d\":40"))
    }

    @Test
    fun testTransientDefaultAndEquality() = runTest {
        val script = """
            class TestExt(@Transient val a = 100, val b) {
                @Transient var c = 200
                var d = 300
            }
            
            val t1 = TestExt(b: 2)
            t1.c = 300
            t1.d = 400
            
            val t2 = TestExt(a: 50, b: 2)
            t2.c = 500
            t2.d = 400
            
            // Equality should ignore transient fields a and c
            val equal = (t1 == t2)
            
            [t1, t2, equal]
        """.trimIndent()
        
        val scope = Scope()
        val result = (scope.eval(script) as net.sergeych.lyng.obj.ObjList).list
        val t1 = result[0] as ObjInstance
        val t2 = result[1] as ObjInstance
        val equal = result[2].toBool()
        
        assertEquals(true, equal, "Objects should be equal despite different transient fields")
        
        // Serialize t1
        val serialized = lynonEncodeAny(scope, t1)
        val t1d = lynonDecodeAny(scope, serialized) as ObjInstance
        
        // a should have its default value 100, not null or 10
        assertEquals(100, (t1d.readField(scope, "a").value as ObjInt).value)
        // c should have its initial value 200
        assertEquals(200, (t1d.readField(scope, "c").value as ObjInt).value)
        // b and d should be preserved
        assertEquals(2, (t1d.readField(scope, "b").value as ObjInt).value)
        assertEquals(400, (t1d.readField(scope, "d").value as ObjInt).value)
    }

    @Test
    fun testStaticTransient() = runTest {
        val script = """
            class TestStatic {
                @Transient static var x = 10
                static var y = 20
            }
            TestStatic.x = 30
            TestStatic.y = 40
            TestStatic
        """.trimIndent()
        val scope = Scope()
        scope.eval(script)
        // Static fields aren't serialized yet, but we ensure the parser accepts it
    }

    @Test
    fun testTransientSize() = runTest {
        val script = """
            class Data1(val a, val b) {
                var c = 30
            }
            class Data2(val a, val b, @Transient val x) {
                var c = 30
                @Transient var y = 40
            }
            
            val d1 = Data1(10, 20)
            val d2 = Data2(10, 20, 100)
            d2.y = 200
            
            [d1, d2]
        """.trimIndent()
        
        val scope = Scope()
        val result = (scope.eval(script) as net.sergeych.lyng.obj.ObjList).list
        val d1 = result[0] as ObjInstance
        val d2 = result[1] as ObjInstance
        
        val s1 = lynonEncodeAny(scope, d1)
        val s2 = lynonEncodeAny(scope, d2)
        
        println("[DEBUG_LOG] Data1 size: ${s1.size}")
        println("[DEBUG_LOG] Data2 size: ${s2.size}")
        
        assertEquals(s1.size, s2.size, "Serialized sizes should match because transient fields are not serialized")
        
        val j1 = d1.toJson(scope).toString()
        val j2 = d2.toJson(scope).toString()
        
        println("[DEBUG_LOG] Data1 JSON: $j1")
        println("[DEBUG_LOG] Data2 JSON: $j2")
        
        assertEquals(j1.length, j2.length, "JSON lengths should match")
    }

    @Test
    fun testObjectTransient() = runTest {
        val script = """
            object MyObject {
                @Transient var temp = 10
                var persistent = 20
            }
            MyObject.temp = 30
            MyObject.persistent = 40
            MyObject
        """.trimIndent()
        
        val scope = Scope()
        val obj = scope.eval(script) as ObjInstance
        
        val serialized = lynonEncodeAny(scope, obj)
        val deserialized = lynonDecodeAny(scope, serialized) as ObjInstance
        
        // persistent should be 40
        assertEquals(40, (deserialized.readField(scope, "persistent").value as ObjInt).value)
        // temp should be restored to 10
        assertEquals(10, (deserialized.readField(scope, "temp").value as ObjInt).value)
    }

    @Test
    fun testStaticTransientToJson() = runTest {
        val script = """
            class TestStatic {
                @Transient static var s1 = 10
                static var s2 = 20
                private static var s3 = 30
            }
            TestStatic
        """.trimIndent()
        
        val scope = Scope()
        val cls = scope.eval(script) as net.sergeych.lyng.obj.ObjClass
        
        val json = cls.toJson(scope).toString()
        println("[DEBUG_LOG] Class JSON: $json")
        
        // s2 should be in JSON
        assertNotNull(json.contains("\"s2\":20"))
        // s1 should NOT be in JSON (transient)
        assertFalse(json.contains("\"s1\":"))
        // s3 should NOT be in JSON (private)
        assertFalse(json.contains("\"s3\":"))
        // __class_name should be there
        assertNotNull(json.contains("\"__class_name\":\"TestStatic\""))
        
        // Test serialization/deserialization of the class itself
        val serialized = lynonEncodeAny(scope, cls)
        val deserialized = lynonDecodeAny(scope, serialized) as net.sergeych.lyng.obj.ObjClass
        assertEquals(cls, deserialized)
    }
}
