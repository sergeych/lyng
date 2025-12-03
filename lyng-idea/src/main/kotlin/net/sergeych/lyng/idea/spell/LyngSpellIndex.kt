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
package net.sergeych.lyng.idea.spell

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Per-file cached spellcheck index built from MiniAst-based highlighting and the lynglib highlighter.
 * It exposes identifier, comment, and string literal ranges. Strategies should suspend until data is ready.
 */
object LyngSpellIndex {
    private val LOG = Logger.getInstance(LyngSpellIndex::class.java)

    data class Data(
        val modStamp: Long,
        val identifiers: List<TextRange>,
        val comments: List<TextRange>,
        val strings: List<TextRange>,
    )

    private val KEY: Key<Data> = Key.create("LYNG_SPELL_INDEX")

    fun getUpToDate(file: PsiFile): Data? {
        val doc = file.viewProvider.document ?: return null
        val d = file.getUserData(KEY) ?: return null
        return if (d.modStamp == doc.modificationStamp) d else null
    }

    fun store(file: PsiFile, data: Data) {
        file.putUserData(KEY, data)
        LOG.info("LyngSpellIndex built: ids=${data.identifiers.size}, comments=${data.comments.size}, strings=${data.strings.size}")
    }
}
