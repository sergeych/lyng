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

    override fun getDisplayName(): String = "Lyng Formatter"

    override fun createComponent(): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        spacingCb = JCheckBox("Enable spacing normalization (commas/operators/colons/keyword parens)")
        wrappingCb = JCheckBox("Enable line wrapping (120 cols) [experimental]")
        reindentClosedBlockCb = JCheckBox("Reindent enclosed block on Enter after '}'")
        reindentPasteCb = JCheckBox("Reindent pasted blocks (align pasted code to current indent)")
        normalizeBlockCommentIndentCb = JCheckBox("Normalize block comment indentation [experimental]")

        // Tooltips / short help
        spacingCb?.toolTipText = "Applies minimal, safe spacing (e.g., around commas/operators, control-flow parens)."
        wrappingCb?.toolTipText = "Experimental: wrap long argument lists to keep lines under ~120 columns."
        reindentClosedBlockCb?.toolTipText = "On Enter after a closing '}', reindent the just-closed {…} block using formatter rules."
        reindentPasteCb?.toolTipText = "When caret is in leading whitespace, reindent the pasted text and align it to the caret's indent."
        normalizeBlockCommentIndentCb?.toolTipText = "Experimental: normalize indentation inside /* … */ comments (code is not modified)."
        p.add(spacingCb)
        p.add(wrappingCb)
        p.add(reindentClosedBlockCb)
        p.add(reindentPasteCb)
        p.add(normalizeBlockCommentIndentCb)
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
                normalizeBlockCommentIndentCb?.isSelected != s.normalizeBlockCommentIndent
    }

    override fun apply() {
        val s = LyngFormatterSettings.getInstance(project)
        s.enableSpacing = spacingCb?.isSelected == true
        s.enableWrapping = wrappingCb?.isSelected == true
        s.reindentClosedBlockOnEnter = reindentClosedBlockCb?.isSelected == true
        s.reindentPastedBlocks = reindentPasteCb?.isSelected == true
        s.normalizeBlockCommentIndent = normalizeBlockCommentIndentCb?.isSelected == true
    }

    override fun reset() {
        val s = LyngFormatterSettings.getInstance(project)
        spacingCb?.isSelected = s.enableSpacing
        wrappingCb?.isSelected = s.enableWrapping
        reindentClosedBlockCb?.isSelected = s.reindentClosedBlockOnEnter
        reindentPasteCb?.isSelected = s.reindentPastedBlocks
        normalizeBlockCommentIndentCb?.isSelected = s.normalizeBlockCommentIndent
    }
}
