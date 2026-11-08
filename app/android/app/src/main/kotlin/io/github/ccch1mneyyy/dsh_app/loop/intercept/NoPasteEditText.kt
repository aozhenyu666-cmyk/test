package io.github.ccch1mneyyy.dsh_app.loop.intercept

import android.content.Context
import android.widget.EditText

/**
 * D5: the break-glass reason must be typed by hand.
 *
 * Long-press/selection menus are switched off, and the system paste/cut/copy actions are
 * refused, so text cannot be pasted from the clipboard through the context menu or selection
 * handles. Typing (including IME composition) is untouched.
 */
class NoPasteEditText(context: Context) : EditText(context) {
    init {
        isLongClickable = false
        setTextIsSelectable(false)
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        if (blocks(id)) false else super.onTextContextMenuItem(id)

    companion object {
        fun blocks(id: Int): Boolean =
            id == android.R.id.paste ||
                id == android.R.id.pasteAsPlainText ||
                id == android.R.id.cut ||
                id == android.R.id.copy
    }
}
