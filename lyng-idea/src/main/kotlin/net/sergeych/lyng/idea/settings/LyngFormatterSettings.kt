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
        var spellCheckStringLiterals: Boolean = true,
        // When Grazie/Natural Languages is present, prefer it for comments and literals (avoid legacy duplicates)
        var preferGrazieForCommentsAndLiterals: Boolean = true,
        // When Grazie is available, also check identifiers via Grazie.
        // Default OFF because Grazie typically doesn't flag code identifiers; legacy Spellchecker is better for code.
        var grazieChecksIdentifiers: Boolean = false,
        // Grazie-only fallback: treat identifiers as comments domain so Grazie applies spelling rules
        var grazieTreatIdentifiersAsComments: Boolean = true,
        // Grazie-only fallback: treat string literals as comments domain when LITERALS domain is not requested
        var grazieTreatLiteralsAsComments: Boolean = true,
        // Debug helper: show the exact ranges we feed to Grazie/legacy as weak warnings
        var debugShowSpellFeed: Boolean = false,
        // Visuals: render Lyng typos using the standard Typo green underline styling
        var showTyposWithGreenUnderline: Boolean = true,
        // Enable lightweight quick-fixes (Replace..., Add to dictionary) without legacy Spell Checker
        var offerLyngTypoQuickFixes: Boolean = true,
        // Per-project learned words (do not flag again)
        var learnedWords: MutableSet<String> = mutableSetOf(),
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

    var spellCheckStringLiterals: Boolean
        get() = myState.spellCheckStringLiterals
        set(value) { myState.spellCheckStringLiterals = value }

    var preferGrazieForCommentsAndLiterals: Boolean
        get() = myState.preferGrazieForCommentsAndLiterals
        set(value) { myState.preferGrazieForCommentsAndLiterals = value }

    var grazieChecksIdentifiers: Boolean
        get() = myState.grazieChecksIdentifiers
        set(value) { myState.grazieChecksIdentifiers = value }

    var grazieTreatIdentifiersAsComments: Boolean
        get() = myState.grazieTreatIdentifiersAsComments
        set(value) { myState.grazieTreatIdentifiersAsComments = value }

    var grazieTreatLiteralsAsComments: Boolean
        get() = myState.grazieTreatLiteralsAsComments
        set(value) { myState.grazieTreatLiteralsAsComments = value }

    var debugShowSpellFeed: Boolean
        get() = myState.debugShowSpellFeed
        set(value) { myState.debugShowSpellFeed = value }

    var showTyposWithGreenUnderline: Boolean
        get() = myState.showTyposWithGreenUnderline
        set(value) { myState.showTyposWithGreenUnderline = value }

    var offerLyngTypoQuickFixes: Boolean
        get() = myState.offerLyngTypoQuickFixes
        set(value) { myState.offerLyngTypoQuickFixes = value }

    var learnedWords: MutableSet<String>
        get() = myState.learnedWords
        set(value) { myState.learnedWords = value }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): LyngFormatterSettings = project.getService(LyngFormatterSettings::class.java)
    }
}
