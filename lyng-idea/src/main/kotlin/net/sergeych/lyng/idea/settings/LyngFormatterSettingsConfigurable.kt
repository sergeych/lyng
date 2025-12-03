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

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class LyngFormatterSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private var spacingCb: JCheckBox? = null
    private var wrappingCb: JCheckBox? = null
    private var reindentClosedBlockCb: JCheckBox? = null
    private var reindentPasteCb: JCheckBox? = null
    private var normalizeBlockCommentIndentCb: JCheckBox? = null
    private var spellCheckLiteralsCb: JCheckBox? = null
    private var preferGrazieCommentsLiteralsCb: JCheckBox? = null
    private var grazieChecksIdentifiersCb: JCheckBox? = null
    private var grazieIdsAsCommentsCb: JCheckBox? = null
    private var grazieLiteralsAsCommentsCb: JCheckBox? = null
    private var debugShowSpellFeedCb: JCheckBox? = null
    private var showTyposGreenCb: JCheckBox? = null
    private var offerQuickFixesCb: JCheckBox? = null

    override fun getDisplayName(): String = "Lyng Formatter"

    override fun createComponent(): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        spacingCb = JCheckBox("Enable spacing normalization (commas/operators/colons/keyword parens)")
        wrappingCb = JCheckBox("Enable line wrapping (120 cols) [experimental]")
        reindentClosedBlockCb = JCheckBox("Reindent enclosed block on Enter after '}'")
        reindentPasteCb = JCheckBox("Reindent pasted blocks (align pasted code to current indent)")
        normalizeBlockCommentIndentCb = JCheckBox("Normalize block comment indentation [experimental]")
        spellCheckLiteralsCb = JCheckBox("Spell check string literals (skip % specifiers like %s, %d, %-12s)")
        preferGrazieCommentsLiteralsCb = JCheckBox("Prefer Natural Languages/Grazie for comments and string literals (avoid duplicates)")
        grazieChecksIdentifiersCb = JCheckBox("Check identifiers via Natural Languages/Grazie when available")
        grazieIdsAsCommentsCb = JCheckBox("Natural Languages/Grazie: treat identifiers as comments (forces spelling checks in 2024.3)")
        grazieLiteralsAsCommentsCb = JCheckBox("Natural Languages/Grazie: treat string literals as comments when literals are not processed")
        debugShowSpellFeedCb = JCheckBox("Debug: show spell-feed ranges (weak warnings)")
        showTyposGreenCb = JCheckBox("Show Lyng typos with green underline (TYPO styling)")
        offerQuickFixesCb = JCheckBox("Offer Lyng typo quick fixes (Replace…, Add to dictionary) without Spell Checker")

        // Tooltips / short help
        spacingCb?.toolTipText = "Applies minimal, safe spacing (e.g., around commas/operators, control-flow parens)."
        wrappingCb?.toolTipText = "Experimental: wrap long argument lists to keep lines under ~120 columns."
        reindentClosedBlockCb?.toolTipText = "On Enter after a closing '}', reindent the just-closed {…} block using formatter rules."
        reindentPasteCb?.toolTipText = "When caret is in leading whitespace, reindent the pasted text and align it to the caret's indent."
        normalizeBlockCommentIndentCb?.toolTipText = "Experimental: normalize indentation inside /* … */ comments (code is not modified)."
        preferGrazieCommentsLiteralsCb?.toolTipText = "When ON and Natural Languages/Grazie is installed, comments and string literals are checked by Grazie. Turn OFF to force legacy Spellchecker to check them."
        grazieChecksIdentifiersCb?.toolTipText = "When ON and Natural Languages/Grazie is installed, identifiers (non-keywords) are checked by Grazie too."
        grazieIdsAsCommentsCb?.toolTipText = "Grazie-only fallback: route identifiers as COMMENTS domain so Grazie applies spelling in 2024.3."
        grazieLiteralsAsCommentsCb?.toolTipText = "Grazie-only fallback: when Grammar doesn't process literals, route strings as COMMENTS so they are checked."
        debugShowSpellFeedCb?.toolTipText = "Show the exact ranges we feed to spellcheckers (ids/comments/strings) as weak warnings."
        showTyposGreenCb?.toolTipText = "Render Lyng typos using the platform's green TYPO underline instead of generic warnings."
        offerQuickFixesCb?.toolTipText = "Provide lightweight Replace… and Add to dictionary quick-fixes without requiring the legacy Spell Checker."
        p.add(spacingCb)
        p.add(wrappingCb)
        p.add(reindentClosedBlockCb)
        p.add(reindentPasteCb)
        p.add(normalizeBlockCommentIndentCb)
        p.add(spellCheckLiteralsCb)
        p.add(preferGrazieCommentsLiteralsCb)
        p.add(grazieChecksIdentifiersCb)
        p.add(grazieIdsAsCommentsCb)
        p.add(grazieLiteralsAsCommentsCb)
        p.add(debugShowSpellFeedCb)
        p.add(showTyposGreenCb)
        p.add(offerQuickFixesCb)
        panel = p
        reset()
        return p
    }

    override fun isModified(): Boolean {
        val s = LyngFormatterSettings.getInstance(project)
        return spacingCb?.isSelected != s.enableSpacing ||
                wrappingCb?.isSelected != s.enableWrapping ||
                reindentClosedBlockCb?.isSelected != s.reindentClosedBlockOnEnter ||
                reindentPasteCb?.isSelected != s.reindentPastedBlocks ||
                normalizeBlockCommentIndentCb?.isSelected != s.normalizeBlockCommentIndent ||
                spellCheckLiteralsCb?.isSelected != s.spellCheckStringLiterals ||
                preferGrazieCommentsLiteralsCb?.isSelected != s.preferGrazieForCommentsAndLiterals ||
                grazieChecksIdentifiersCb?.isSelected != s.grazieChecksIdentifiers ||
                grazieIdsAsCommentsCb?.isSelected != s.grazieTreatIdentifiersAsComments ||
                grazieLiteralsAsCommentsCb?.isSelected != s.grazieTreatLiteralsAsComments ||
                debugShowSpellFeedCb?.isSelected != s.debugShowSpellFeed ||
                showTyposGreenCb?.isSelected != s.showTyposWithGreenUnderline ||
                offerQuickFixesCb?.isSelected != s.offerLyngTypoQuickFixes
    }

    override fun apply() {
        val s = LyngFormatterSettings.getInstance(project)
        s.enableSpacing = spacingCb?.isSelected == true
        s.enableWrapping = wrappingCb?.isSelected == true
        s.reindentClosedBlockOnEnter = reindentClosedBlockCb?.isSelected == true
        s.reindentPastedBlocks = reindentPasteCb?.isSelected == true
        s.normalizeBlockCommentIndent = normalizeBlockCommentIndentCb?.isSelected == true
        s.spellCheckStringLiterals = spellCheckLiteralsCb?.isSelected == true
        s.preferGrazieForCommentsAndLiterals = preferGrazieCommentsLiteralsCb?.isSelected == true
        s.grazieChecksIdentifiers = grazieChecksIdentifiersCb?.isSelected == true
        s.grazieTreatIdentifiersAsComments = grazieIdsAsCommentsCb?.isSelected == true
        s.grazieTreatLiteralsAsComments = grazieLiteralsAsCommentsCb?.isSelected == true
        s.debugShowSpellFeed = debugShowSpellFeedCb?.isSelected == true
        s.showTyposWithGreenUnderline = showTyposGreenCb?.isSelected == true
        s.offerLyngTypoQuickFixes = offerQuickFixesCb?.isSelected == true
    }

    override fun reset() {
        val s = LyngFormatterSettings.getInstance(project)
        spacingCb?.isSelected = s.enableSpacing
        wrappingCb?.isSelected = s.enableWrapping
        reindentClosedBlockCb?.isSelected = s.reindentClosedBlockOnEnter
        reindentPasteCb?.isSelected = s.reindentPastedBlocks
        normalizeBlockCommentIndentCb?.isSelected = s.normalizeBlockCommentIndent
        spellCheckLiteralsCb?.isSelected = s.spellCheckStringLiterals
        preferGrazieCommentsLiteralsCb?.isSelected = s.preferGrazieForCommentsAndLiterals
        grazieChecksIdentifiersCb?.isSelected = s.grazieChecksIdentifiers
        grazieIdsAsCommentsCb?.isSelected = s.grazieTreatIdentifiersAsComments
        grazieLiteralsAsCommentsCb?.isSelected = s.grazieTreatLiteralsAsComments
        debugShowSpellFeedCb?.isSelected = s.debugShowSpellFeed
        showTyposGreenCb?.isSelected = s.showTyposWithGreenUnderline
        offerQuickFixesCb?.isSelected = s.offerLyngTypoQuickFixes
    }
}
