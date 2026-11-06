package app.lumen.tv.ui

import android.view.View

/** Ingrandisce leggermente l'elemento selezionato con il telecomando; [onFocus] avvisa chi deve aggiornare l'anteprima. */
fun View.scaleOnFocus(scale: Float = 1.08f, onFocus: ((View) -> Unit)? = null) {
    setOnFocusChangeListener { view, focused ->
        val target = if (focused) scale else 1f
        view.animate().scaleX(target).scaleY(target).setDuration(140).start()
        view.translationZ = if (focused) 12f else 0f
        if (focused) onFocus?.invoke(view)
    }
}
