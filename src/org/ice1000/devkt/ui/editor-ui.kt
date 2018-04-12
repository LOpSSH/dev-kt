package org.ice1000.devkt.ui

import org.ice1000.devkt.*
import org.ice1000.devkt.config.ColorScheme
import org.ice1000.devkt.config.GlobalSettings
import org.ice1000.devkt.lang.*
import org.jetbrains.kotlin.com.intellij.psi.*
import javax.swing.JTextPane

interface DevKtDocument<in TextAttributes> : LengthOwner {
	var caretPosition: Int
	fun clear() = remove(0, length)
	fun remove(offs: Int, len: Int)
	fun insert(offs: Int, str: String)
	fun changeCharacterAttributes(offset: Int, length: Int, s: TextAttributes, replace: Boolean)
	fun changeParagraphAttributes(offset: Int, length: Int, s: TextAttributes, replace: Boolean)
	fun resetLineNumberLabel(str: String)
	fun lockWrite()
	fun unlockWrite()
	fun message(text: String)
}

class DevKtDocumentHandler<in TextAttributes>(
		private val document: DevKtDocument<TextAttributes>,
		private val colorScheme: ColorScheme<TextAttributes>) : AnnotationHolder<TextAttributes> {
	init {
		adjustFormat()
	}

	private var selfMaintainedString = StringBuilder()
	private val languages = listOf(
			Java<TextAttributes>(JavaAnnotator(), JavaSyntaxHighlighter()),
			Kotlin<TextAttributes>(KotlinAnnotator(), KotlinSyntaxHighlighter())
	)

	private var currentLanguage: ProgrammingLanguage<TextAttributes>? = null
	private var psiFileCache: PsiFile? = null
	private val highlightCache = ArrayList<TextAttributes?>(5000)
	private var lineNumber = 1

	override val text get() = selfMaintainedString.toString()
	override fun getLength() = document.length

	fun switchLanguage(fileName: String) {
		currentLanguage = languages.firstOrNull { it.satisfies(fileName) }
	}

	fun adjustFormat(offs: Int = 0, len: Int = document.length - offs) {
		if (len <= 0) return
		document.changeParagraphAttributes(offs, len, colorScheme.tabSize, false)
		val currentLineNumber = selfMaintainedString.count { it == '\n' } + 1
		val change = currentLineNumber != lineNumber
		lineNumber = currentLineNumber
		//language=HTML
		if (change) document.resetLineNumberLabel((1..currentLineNumber).joinToString(
				separator = "<br/>", prefix = "<html>", postfix = "&nbsp;</html>"))
	}

	fun clear() = remove(0, document.length)
	fun remove(offs: Int, len: Int) {
		val delString = this.text.substring(offs, offs + len)        //即将被删除的字符串
		val (offset, length) = when {
			delString in paired            //是否存在于字符对里
					&& text.getOrNull(offs + 1)?.toString() == paired[delString] -> {
				offs to 2
			}
			else -> offs to len
		}

		selfMaintainedString.delete(offset, offset + length)
		with(document) {
			remove(offset, length)
			reparse()
			adjustFormat(offset, length)
		}
	}

	fun insert(offs: Int, str: String) {
		val normalized = str.filterNot { it == '\r' }
		val (offset, string, move) = when {
			normalized.length > 1 -> Triple(offs, normalized, 0)
			normalized in paired.values -> {
				val another = paired.keys.first { paired[it] == normalized }
				if (offs != 0
						&& selfMaintainedString.substring(offs - 1, 1) == another
						&& selfMaintainedString.substring(offs, 1) == normalized) {
					Triple(offs, "", 1)
				} else Triple(offs, normalized, 0)
			}
			normalized in paired -> Triple(offs, normalized + paired[normalized], -1)
			else -> Triple(offs, normalized, 0)
		}
		selfMaintainedString.insert(offset, string)
		with(document) {
			insert(offset, string)
			caretPosition += move
			reparse()
			adjustFormat(offset, string.length)
		}
	}

	fun reparse() {
		while (highlightCache.size <= document.length) highlightCache.add(null)
		// val time = System.currentTimeMillis()
		if (GlobalSettings.highlightTokenBased) lex()
		// val time2 = System.currentTimeMillis()
		if (GlobalSettings.highlightSemanticBased) parse()
		// val time3 = System.currentTimeMillis()
		rehighlight()
		// benchmark
		// println("${time2 - time}, ${time3 - time2}, ${System.currentTimeMillis() - time3}")
	}

	private fun lex() {
		val tokens = Analyzer.lex(text)
		for ((start, end, _, type) in tokens)
			currentLanguage?.attributesOf(type, colorScheme)?.let { highlight(start, end, it) }
	}

	/**
	 * @see com.intellij.lang.annotation.AnnotationHolder.createAnnotation
	 */
	override fun highlight(tokenStart: Int, tokenEnd: Int, attributeSet: TextAttributes) {
		if (tokenStart >= tokenEnd) return
		for (i in tokenStart until tokenEnd) highlightCache[i] = attributeSet
	}

	private fun parse() {
		SyntaxTraverser
				.psiTraverser(Analyzer.parseKotlin(text).also { psiFileCache = it })
				.forEach { psi ->
					if (psi !is PsiWhiteSpace) currentLanguage?.annotate(psi, this, colorScheme)
				}
	}

	private fun rehighlight() {
		if (document.length > 1) try {
			document.lockWrite()
			var tokenStart = 0
			var attributeSet = highlightCache[0]
			highlightCache[0] = null
			for (i in 1 until highlightCache.size) {
				if (attributeSet != highlightCache[i]) {
					if (attributeSet != null)
						document.changeCharacterAttributes(tokenStart, i - tokenStart, attributeSet, true)
					tokenStart = i
					attributeSet = highlightCache[i]
				}
				highlightCache[i] = null
			}
		} finally {
			document.unlockWrite()
		}
	}

	fun nextLine() {
		document.message("Started new line")
		val index = document.caretPosition
		val endOfLineIndex = selfMaintainedString.indexOf('\n', index)
		document.insert(if (endOfLineIndex < 0) document.length else endOfLineIndex, "\n")
		document.caretPosition = endOfLineIndex + 1
	}

	fun splitLine() {
		document.message("Split new line")
		val index = document.caretPosition
		document.insert(index, "\n")
		document.caretPosition = index
	}

	fun newLineBeforeCurrent() {
		document.message("Started new line before current line")
		val index = document.caretPosition
		val startOfLineIndex = selfMaintainedString.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).coerceAtLeast(0)
		document.insert(startOfLineIndex, "\n")
		document.caretPosition = startOfLineIndex + 1
	}
}

//FIXME: tab会被当做1个字符, 不知道有没有什么解决办法
fun JTextPane.lineColumnToPos(line: Int, column: Int = 1): Int {
	val lineStart = document.defaultRootElement.getElement(line - 1).startOffset
	return lineStart + column - 1
}

fun JTextPane.posToLineColumn(pos: Int): Pair<Int, Int> {
	val root = document.defaultRootElement
	val line = root.getElementIndex(pos)
	val column = pos - root.getElement(line).startOffset
	return line + 1 to column + 1
}
