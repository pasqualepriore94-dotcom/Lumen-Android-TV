package app.lumen.tv.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.MediaKind
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Row
import app.lumen.tv.data.TitleCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cerca nel catalogo e nella playlist mentre si scrive. */
class SearchActivity : ComponentActivity() {
    private lateinit var app: LumenApp
    private lateinit var rows: RecyclerView
    private lateinit var message: TextView
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        app = application as LumenApp
        rows = findViewById<RecyclerView>(R.id.rows).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            itemAnimator = null
        }
        message = findViewById(R.id.message)
        val query = findViewById<EditText>(R.id.query)
        query.requestFocus()
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty(), delayMs = 500)
        })
        query.setOnEditorActionListener { view, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                search(view.text.toString(), delayMs = 0)
                rows.post { rows.requestFocus() }
                true
            } else false
        }
    }

    private fun search(text: String, delayMs: Long) {
        job?.cancel()
        val q = text.trim()
        if (q.length < 2) {
            rows.adapter = null
            show("Scrivi almeno 2 lettere")
            return
        }
        job = lifecycleScope.launch {
            delay(delayMs)
            show("Cerco…")
            val local = withContext(Dispatchers.Default) { searchPlaylist(q) }
            val catalog = app.tmdb.searchCatalog(q)
            val result = listOfNotNull(
                catalog.takeIf { it.isNotEmpty() }?.let { Row("Catalogo", it) },
                local[MediaKind.MOVIE]?.let { Row("Film nella playlist", it) },
                local[MediaKind.SERIES]?.let { Row("Serie nella playlist", it) },
                local[MediaKind.LIVE]?.let { Row("Canali", it) },
            )
            rows.adapter = RowsAdapter(result) { list ->
                CardAdapter(list, lifecycleScope, app.tmdb, app.epg, { app.library.progressFor(it)?.percent }, ::open)
            }
            if (result.isEmpty()) show("Nessun risultato per “$q”") else message.visibility = View.GONE
        }
    }

    /** Tutte le parole cercate devono comparire nel nome, senza badare ad accenti e maiuscole. */
    private fun searchPlaylist(q: String): Map<MediaKind, List<PlaylistItem>> {
        val words = q.split(Regex("""\s+""")).map(TitleCleaner::key).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyMap()
        return app.playlist.asSequence()
            .filter { item -> val k = TitleCleaner.key(item.name); words.all { it in k } }
            .groupBy { it.kind }
            .mapValues { (_, list) -> list.distinctBy { it.name }.take(MAX_PER_ROW) }
    }

    private fun show(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun open(item: PlaylistItem) {
        if (item.kind == MediaKind.LIVE) {
            startActivity(PlayerActivity.intent(this, app, item, item.url, fromStart = true))
        } else {
            startActivity(DetailActivity.intent(this, item))
        }
    }

    companion object {
        private const val MAX_PER_ROW = 40
    }
}
