package net.sergeych.lyng.io.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyngio.stdlib_included.html_parseLyng

private const val MODULE = "lyng.io.html.parse"

fun createHtmlParseModule(scope: Scope): Boolean = createHtmlParseModule(scope.importManager)

fun createHtmlParseModule(manager: ImportManager): Boolean {
    if (MODULE in manager.packageNames) return false
    manager.addPackage(MODULE) { module -> buildHtmlParseModule(module) }
    return true
}

private suspend fun buildHtmlParseModule(module: ModuleScope) {
    module.eval(Source(MODULE, html_parseLyng))
    val html = ObjClass("Html").apply {
        addClassFn("parse") {
            val source = requiredArg<ObjString>(0).value
            val baseUrl = (args.list.getOrNull(1) as? ObjString)?.value.orEmpty()
            ObjHtmlDocument(Ksoup.parse(source, baseUrl))
        }
    }
    module.addConst("Html", html)
    module.addConst("HtmlDocument", ObjHtmlDocument.type)
    module.addConst("HtmlElement", ObjHtmlElement.type)
}

private class ObjHtmlDocument(val document: Document) : Obj() {
    override val objClass get() = type
    companion object {
        val type = object : ObjClass("HtmlDocument") {
            override suspend fun callOn(scope: Scope): Obj = scope.raiseError("Use Html.parse()")
        }.apply {
            addProperty("title", getter = { ObjString(thisAs<ObjHtmlDocument>().document.title()) })
            addFn("text") { ObjString(readableText(thisAs<ObjHtmlDocument>().document.body())) }
            addFn("select") {
                val css = requiredArg<ObjString>(0).value
                ObjList(thisAs<ObjHtmlDocument>().document.select(css).map { ObjHtmlElement(it) as Obj }.toMutableList())
            }
        }
    }
}

private class ObjHtmlElement(val element: Element) : Obj() {
    override val objClass get() = type
    companion object {
        val type = object : ObjClass("HtmlElement") {
            override suspend fun callOn(scope: Scope): Obj = scope.raiseError("Use document.select()")
        }.apply {
            addFn("text") { ObjString(readableText(thisAs<ObjHtmlElement>().element)) }
            addFn("attr") { ObjString(thisAs<ObjHtmlElement>().element.attr(requiredArg<ObjString>(0).value)) }
            addFn("absUrl") { ObjString(thisAs<ObjHtmlElement>().element.absUrl(requiredArg<ObjString>(0).value)) }
            addFn("select") {
                val css = requiredArg<ObjString>(0).value
                ObjList(thisAs<ObjHtmlElement>().element.select(css).map { ObjHtmlElement(it) as Obj }.toMutableList())
            }
        }
    }
}

private val excludedTags = setOf("script", "style", "template", "noscript")
private val inlineWhitespace = Regex("[\\s\\u00a0]+")

/** Iterative traversal avoids overflowing the host stack on deeply nested input. */
private fun readableText(root: Element): String {
    val text = StringBuilder()
    val pending = ArrayDeque<Pair<Node, Boolean>>()
    pending.addLast(root to false)
    while (pending.isNotEmpty()) {
        val (node, exit) = pending.removeLast()
        when (node) {
            is TextNode -> text.append(node.getWholeText().replace(inlineWhitespace, " "))
            is Element -> {
                if (node.normalName() in excludedTags || node.hasAttr("hidden")) continue
                val block = node.isBlock() || node.normalName() == "br"
                if (block) text.append('\n')
                if (!exit) {
                    if (block) pending.addLast(node to true)
                    for (child in node.childNodes().asReversed()) pending.addLast(child to false)
                }
            }
        }
    }
    return text.toString().lineSequence().map { it.replace(inlineWhitespace, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
