/*
 * Copyright 2026 Sergey S. Chernov
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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertFails

class ImmutableCollectionsTest {
    @Test
    fun immutableListSnapshotAndConversion() = runTest {
        eval(
            """
            val src = [1,2,3]
            val imm = src.toImmutable()
            assert(imm is ImmutableList<Int>)
            assert(imm is Array<Int>)
            assert(imm is Collection<Int>)
            assert(imm is Iterable<Int>)

            src += 4
            assertEquals(3, imm.size)
            assertEquals([1,2,3], imm.toMutable())
            assertEquals([1,2,3], (1..3).toImmutableList().toMutable())
            """
        )
    }

    @Test
    fun immutableSetSnapshotAndConversion() = runTest {
        eval(
            """
            val src = Set(1,2,3)
            val imm = src.toImmutable()
            assert(imm is ImmutableSet<Int>)
            assert(imm is Collection<Int>)
            assert(imm is Iterable<Int>)
            src += 4
            assertEquals(3, imm.size)
            assertEquals(Set(1,2,3), imm.toMutable())
            assertEquals(Set(1,2,3), [1,2,3].toImmutableSet.toMutable())
            """
        )
    }

    @Test
    fun immutableMapSnapshotAndConversion() = runTest {
        eval(
            """
            val src = Map("a" => 1, "b" => 2)
            val imm = src.toImmutable()
            assert(imm is ImmutableMap<String,Int>)
            assert(imm is Collection<MapEntry<String,Int>>)
            assert(imm is Iterable<MapEntry<String,Int>>)
            src["a"] = 100
            assertEquals(1, imm["a"])
            assertEquals(Map("a" => 1, "b" => 2), imm.toMutable())

            val imm2 = ["x" => 10, "y" => 20].toImmutableMap
            assertEquals(10, imm2["x"])
            assertEquals(Map("x" => 10, "y" => 20), imm2.toMutable())
            """
        )
    }

    @Test
    fun immutableCollectionsRejectMutationOps() = runTest {
        assertFails {
            eval(
                """
                val xs = ImmutableList(1,2,3)
                xs += 4
                """
            )
        }
        assertFails {
            eval(
                """
                val s = ImmutableSet(1,2,3)
                s += 4
                """
            )
        }
        assertFails {
            eval(
                """
                val m = ImmutableMap("a" => 1)
                m["a"] = 10
                """
            )
        }
    }
}
