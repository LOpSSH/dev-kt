package org.ice1000.devkt

import org.ice1000.devkt.lie.ctrlOrMeta
import java.awt.event.KeyEvent
import javax.swing.JMenuItem
import javax.swing.KeyStroke

data class Quad<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)

val selfLocation: String = Kotlin::class.java.protectionDomain.codeSource.location.file

val paired = mapOf(
		"\"" to "\"",
		"'" to "'",
		"“" to "”",
		"‘" to "’",
		"`" to "`",
		"`" to "`",
		"(" to ")",
		"（" to "）",
		"『" to "』",
		"「" to "」",
		"〖" to "〗",
		"【" to "】",
		"[" to "]",
		"〔" to "〕",
		"［" to "］",
		"{" to "}",
		"｛" to "｝",
		"<" to ">",
		"《" to "》",
		"〈" to "〉",
		"‹" to "›",
		"«" to "»"
)

/**
 * `Ctrl` for Windows/Linux, `Meta` for MacOS
 * Replacement of [java.awt.Toolkit.getMenuShortcutKeyMask]
 * @param key Int
 * @sample KeyEvent.VK_S
 */
fun JMenuItem.controlKey(key: Int) {
	this.accelerator = KeyStroke.getKeyStroke(key, ctrlOrMeta)
}
