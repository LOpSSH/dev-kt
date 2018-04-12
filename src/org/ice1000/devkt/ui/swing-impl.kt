package org.ice1000.devkt.ui

import charlie.gensokyo.show
import net.iharder.dnd.FileDrop
import org.ice1000.devkt.Analyzer
import org.ice1000.devkt.`{-# LANGUAGE SarasaGothicFont #-}`.loadFont
import org.ice1000.devkt.config.GlobalSettings
import org.ice1000.devkt.config.swingColorScheme
import java.awt.Font
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JMenuItem
import javax.swing.event.DocumentEvent
import javax.swing.event.UndoableEditEvent
import javax.swing.text.*
import javax.swing.undo.UndoManager

/**
 * @author ice1000
 * @since v0.0.1
 */
class UIImpl(frame: DevKtFrame) : AbstractUI(frame) {
	private val undoManager = UndoManager()
	private var edited = false
		set(value) {
			val change = field != value
			field = value
			if (change) refreshTitle()
		}

	internal lateinit var saveMenuItem: JMenuItem
	internal lateinit var showInFilesMenuItem: JMenuItem
	private val document: DevKtDocumentHandler<AttributeSet>

	private inner class KtDocument : DefaultStyledDocument(), DevKtDocument<AttributeSet> {
		override var caretPosition
			get() = editor.caretPosition
			set(value) {
				editor.caretPosition = value
			}

		init {
			addUndoableEditListener {
				if (it.source !== this) return@addUndoableEditListener
				undoManager.addEdit(it.edit)
				edited = true
			}
		}

		fun createHandler() = DevKtDocumentHandler(this, swingColorScheme(GlobalSettings, attributeContext))
		override fun lockWrite() = writeLock()
		override fun unlockWrite() = writeUnlock()
		override fun resetLineNumberLabel(str: String) {
			lineNumberLabel.text = str
		}

		override fun insert(offs: Int, str: String) = insertString(offs, str, null)

		/**
		 * Re-implement of [setCharacterAttributes], invoke [fireUndoableEditUpdate] with
		 * [document] as event source, which is used by [undoManager] to prevent color
		 * modifications to be recorded.
		 */
		override fun changeCharacterAttributes(offset: Int, length: Int, s: AttributeSet, replace: Boolean) {
			val changes = DefaultDocumentEvent(offset, length, DocumentEvent.EventType.CHANGE)
			buffer.change(offset, length, changes)
			val sCopy = s.copyAttributes()
			var lastEnd: Int
			var pos = offset
			while (pos < offset + length) {
				val run = getCharacterElement(pos)
				lastEnd = run.endOffset
				if (pos == lastEnd) break
				val attr = run.attributes as MutableAttributeSet
				changes.addEdit(AttributeUndoableEdit(run, sCopy, replace))
				if (replace) attr.removeAttributes(attr)
				attr.addAttributes(s)
				pos = lastEnd
			}
			changes.end()
			fireChangedUpdate(changes)
			fireUndoableEditUpdate(UndoableEditEvent(document, changes))
		}

		/**
		 * Re-implement of [setParagraphAttributes], invoke [fireUndoableEditUpdate] with
		 * [GlobalSettings] as event source, which is used by [undoManager] to prevent color
		 * modifications to be recorded.
		 */
		override fun changeParagraphAttributes(offset: Int, length: Int, s: AttributeSet, replace: Boolean) = try {
			writeLock()
			val changes = DefaultDocumentEvent(offset, length, DocumentEvent.EventType.CHANGE)
			val sCopy = s.copyAttributes()
			val section = defaultRootElement
			for (i in section.getElementIndex(offset)..section.getElementIndex(offset + if (length > 0) length - 1 else 0)) {
				val paragraph = section.getElement(i)
				val attr = paragraph.attributes as MutableAttributeSet
				changes.addEdit(AttributeUndoableEdit(paragraph, sCopy, replace))
				if (replace) attr.removeAttributes(attr)
				attr.addAttributes(s)
			}
			changes.end()
			fireChangedUpdate(changes)
			fireUndoableEditUpdate(UndoableEditEvent(GlobalSettings, changes))
		} finally {
			writeUnlock()
		}
	}

	init {
		mainMenu(menuBar, frame)
		val ktDocument = KtDocument()
		editor.document = ktDocument
		document = ktDocument.createHandler()
		FileDrop(mainPanel) {
			it.firstOrNull { it.canRead() }?.let {
				loadFile(it)
			}
		}
	}

	/**
	 * Should only be called once, extracted from the constructor
	 * to shorten the startup time
	 */
	@JvmName("   ")
	internal fun postInit() {
		init()
		val lastOpenedFile = File(GlobalSettings.lastOpenedFile)
		if (lastOpenedFile.canRead()) {
			edited = false
			loadFile(lastOpenedFile)
		}
	}

	fun createNewFile(templateName: String) {
		if (!makeSureLeaveCurrentFile()) {
			currentFile = null
			edited = true
			document.clear()
			document.insert(0, javaClass
					.getResourceAsStream("/template/$templateName")
					.reader()
					.readText())
		}
	}

	override fun loadFile(it: File) {
		if (it.canRead() and !makeSureLeaveCurrentFile()) {
			currentFile = it
			message("Loaded ${it.absolutePath}")
			val path = it.absolutePath.orEmpty()
			document.clear()
			document.insert(0, it.readText())
			edited = false
			GlobalSettings.lastOpenedFile = path
		}
		updateShowInFilesMenuItem()
	}

	//Shortcuts ↓↓↓
	fun undo() {
		if (undoManager.canUndo()) {
			message("Undo!")
			undoManager.undo()
			edited = true
		}
	}

	fun redo() {
		if (undoManager.canRedo()) {
			message("Redo!")
			undoManager.redo()
			edited = true
		}
	}

	fun selectAll() {
		message("Select All")
		editor.selectAll()
	}

	fun cut() {
		message("Cut selection")
		editor.cut()
	}

	fun copy() {
		message("Copied selection")
		editor.copy()
	}

	fun paste() {
		message("Pasted to current position")
		editor.paste()
	}

	fun gotoLine() {
		GoToLineDialog(this@UIImpl, editor).show
	}

	fun save() {
		val file = currentFile ?: JFileChooser(GlobalSettings.recentFiles.firstOrNull()?.parentFile).apply {
			showSaveDialog(mainPanel)
			fileSelectionMode = JFileChooser.FILES_ONLY
		}.selectedFile ?: return
		currentFile = file
		if (!file.exists()) file.createNewFile()
		GlobalSettings.recentFiles.add(file)
		file.writeText(editor.text) // here, it is better to use `editor.text` instead of `document.text`
		message("Saved to ${file.absolutePath}")
		edited = false
	}

	//这三个方法应该可以合并成一个方法吧
	fun nextLine() = document.nextLine()

	fun splitLine() = document.splitLine()
	fun newLineBeforeCurrent() = document.newLineBeforeCurrent()

	//TODO 暂时还不支持多行注释 QAQ
	fun comment() {
		val offs = editor.caretPosition
		val root = editor.document.defaultRootElement
		val lineCount = root.getElementIndex(offs)
		val lineStart = root.getElement(lineCount).startOffset
		val lineEnd = root.getElement(lineCount).endOffset
		val currentLineText = editor.document.getText(lineStart, lineEnd - lineStart)
		if (currentLineText.startsWith("//")) document.remove(lineStart, 2)
		else document.insert(lineStart, "//")
	}

	//Shortcuts ↑↑↑

	override fun ktFile() = ktFileCache ?: Analyzer.parseKotlin(document.text)

	override fun makeSureLeaveCurrentFile() =
			edited && super.makeSureLeaveCurrentFile()

	fun buildClassAndRun() {
		buildAsClasses { if (it) runCommand(Analyzer.targetDir) }
	}

	fun buildJarAndRun() {
		buildAsJar { if (it) runCommand(Analyzer.targetJar) }
	}

	override fun updateShowInFilesMenuItem() {
		val currentFileNotNull = currentFile != null
		showInFilesMenuItem.isEnabled = currentFileNotNull
		// saveMenuItem.isEnabled = currentFileNotNull
	}

	/**
	 * Just to reuse some codes in [reloadSettings] and [postInit]
	 */
	private fun init() {
		refreshLineNumber()
		memoryIndicator.font = messageLabel.font.run { deriveFont(size2D - 4) }
	}

	override fun reloadSettings() {
		frame.bounds = GlobalSettings.windowBounds
		imageCache = null
		loadFont()
		refreshTitle()
		init()
		with(document) {
			adjustFormat()
			reparse()
		}
	}

	override fun refreshTitle() {
		frame.title = buildString {
			if (edited) append("*")
			append(currentFile?.absolutePath ?: "Untitled")
			append(" - ")
			append(GlobalSettings.appName)
		}
	}

	var editorFont: Font
		set(value) {
			editor.font = value
		}
		get() = editor.font
}