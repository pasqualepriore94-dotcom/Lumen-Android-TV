package app.lumen.tv.ui

import android.view.View

/** Ingrandisce leggermente l'elemento selezionato con il telecomando. */
fun View.scaleOnFocus(scale: Float = 1.08f) {
    setOnFocusChangeListener { view, focused ->
        val target = if (focused) scale else 1f
        view.animate().scaleX(target).scaleY(target).setDuration(120).start()
        view.translationZ = if (focused) 8f else 0f
    }
}
