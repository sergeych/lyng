/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
package net.sergeych.lyng.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "LyngFormatterSettings", storages = [Storage("lyng_idea.xml")])
class LyngFormatterSettings(private val project: Project) : PersistentStateComponent<LyngFormatterSettings.State> {

    data class State(
        var enableSpacing: Boolean = false,
        var enableWrapping: Boolean = false,
        var reindentClosedBlockOnEnter: Boolean = true,
        var reindentPastedBlocks: Boolean = true,
        var normalizeBlockCommentIndent: Boolean = false,
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var enableSpacing: Boolean
        get() = myState.enableSpacing
        set(value) { myState.enableSpacing = value }

    var enableWrapping: Boolean
        get() = myState.enableWrapping
        set(value) { myState.enableWrapping = value }

    var reindentClosedBlockOnEnter: Boolean
        get() = myState.reindentClosedBlockOnEnter
        set(value) { myState.reindentClosedBlockOnEnter = value }

    var reindentPastedBlocks: Boolean
        get() = myState.reindentPastedBlocks
        set(value) { myState.reindentPastedBlocks = value }

    var normalizeBlockCommentIndent: Boolean
        get() = myState.normalizeBlockCommentIndent
        set(value) { myState.normalizeBlockCommentIndent = value }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): LyngFormatterSettings = project.getService(LyngFormatterSettings::class.java)
    }
}
