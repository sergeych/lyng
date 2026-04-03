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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class InferredCtorFieldIntRegressionTest {

    @Test
    fun inferredIntFieldsFromCtorParamsStayIntInsideTypedCalls() = runTest {
        eval(
            """
            class GameState(
                pieceId0: Int,
                nextId0: Int,
                next2Id0: Int,
                px0: Int,
                py0: Int,
            ) {
                var pieceId = pieceId0
                var nextId = nextId0
                var next2Id = next2Id0
                var rot = 0
                var px = px0
                var py = py0
                var score = 0
                var totalLines = 0
                var level = 1
                var running = true
                var gameOver = false
                var paused = false
            }

            fun canPlace(board: List<List<Int>>, boardW: Int, boardH: Int, pieceId: Int, rot: Int, px: Int, py: Int): Bool {
                true
            }

            val board: List<List<Int>> = []
            val boardW = 10
            val boardH = 20

            fun applyKeyInput(s: GameState, key: String) {
                if (key == "ArrowLeft") {
                    if (canPlace(board, boardW, boardH, s.pieceId, s.rot, s.px - 1, s.py) == true) s.px--
                }
            }

            val s = GameState(1, 2, 3, 4, 5)
            applyKeyInput(s, "ArrowLeft")
            assertEquals(3, s.px)
            """.trimIndent()
        )
    }
}
