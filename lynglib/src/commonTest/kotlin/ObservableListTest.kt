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

class ObservableListTest {
    @Test
    fun observableHooksAndSubscriptionCancel() = runTest {
        eval(
            """
            import lyng.observable

            val src = [1,2]
            val xs = src.observable()
            assert(xs is ObservableList<Int>)
            assert(src == [1,2])
            assert(xs == [1,2])

            var before = 0
            var after = 0
            val beforeSub = xs.beforeChange { before++ }
            val afterSub = xs.onChange { after++ }

            xs += 3
            assertEquals([1,2,3], xs)
            assertEquals(1, before)
            assertEquals(1, after)

            afterSub.cancel()
            xs[0] = 100
            assertEquals([100,2,3], xs)
            assertEquals(2, before)
            assertEquals(1, after)

            beforeSub.cancel()
            xs.removeAt(0)
            assertEquals([2,3], xs)
            assertEquals(2, before)
            assertEquals(1, after)
            """
        )
    }

    @Test
    fun observableBeforeChangeRejectsMutation() = runTest {
        eval(
            """
            import lyng.observable

            val xs = [1,2].observable()
            var after = 0
            xs.beforeChange {
                throw ChangeRejectionException("no changes accepted")
            }
            xs.onChange { after++ }

            assertThrows(ChangeRejectionException) {
                xs += 3
            }
            assertEquals([1,2], xs)
            assertEquals(0, after)
            """
        )
    }

    @Test
    fun observableChangesFlowEmitsCommittedEvents() = runTest {
        eval(
            """
            import lyng.observable

            val xs = [10,20].observable()
            val it = xs.changes().iterator()

            xs += 30
            assert(it.hasNext())
            val e1 = it.next()
            assert(e1 is ListInsert<Int>)
            assertEquals(2, (e1 as ListInsert<Int>).index)
            assertEquals([30], e1.values)

            xs[1] = 200
            assert(it.hasNext())
            val e2 = it.next()
            assert(e2 is ListSet<Int>)
            assertEquals(1, (e2 as ListSet<Int>).index)
            assertEquals(20, e2.oldValue)
            assertEquals(200, e2.newValue)

            xs.removeAt(0)
            assert(it.hasNext())
            val e3 = it.next()
            assert(e3 is ListRemove<Int>)
            assertEquals(0, (e3 as ListRemove<Int>).index)
            assertEquals(10, e3.oldValue)

            it.cancelIteration()
            """
        )
    }
}
