package app.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.PlaylistItem
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.TitleCleaner
import app.lumen.tv.data.TmdbClient
import coil.load
import kotlinx.coroutines.launch
import java.util.Locale

class DetailActivity : ComponentActivity() {
    private lateinit var url: String
    private lateinit var name: String
    private lateinit var play: Button
    private lateinit var restart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val logo = intent.getStringExtra(EXTRA_LOGO)
        val group = intent.getStringExtra(EXTRA_GROUP).orEmpty()

        val title = findViewById<TextView>(R.id.title)
        val meta = findViewById<TextView>(R.id.meta)
        val overview = findViewById<TextView>(R.id.overview)
        val backdrop = findViewById<ImageView>(R.id.backdrop)
        play = findViewById(R.id.play)
        restart = findViewById(R.id.restart)

        title.text = TitleCleaner.clean(name).first.ifBlank { name }
        meta.text = group
        logo?.let { backdrop.load(it) }
        play.setOnClickListener { startActivity(PlayerActivity.intent(this, name, url, live = false, fromStart = false)) }
        restart.setOnClickListener { startActivity(PlayerActivity.intent(this, name, url, live = false, fromStart = true)) }
        play.requestFocus()

        val app = application as LumenApp
        lifecycleScope.launch {
            val info = TmdbClient(app.http, app.isLowRam).lookup(name) ?: return@launch
            title.text = info.title
            overview.text = info.overview
            meta.text = listOfNotNull(
                info.year,
                info.rating?.let { String.format(Locale.ITALY, "★ %.1f", it) },
                group,
            ).joinToString("  ·  ")
            (info.backdrop ?: info.poster)?.let { backdrop.load(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        val saved = Prefs(this).position(url) > 0
        play.text = if (saved) "Riprendi" else "Riproduci"
        restart.visibility = if (saved) View.VISIBLE else View.GONE
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_GROUP = "group"

        fun intent(context: Context, item: PlaylistItem) = Intent(context, DetailActivity::class.java)
            .putExtra(EXTRA_NAME, item.name)
            .putExtra(EXTRA_URL, item.url)
            .putExtra(EXTRA_LOGO, item.logo)
            .putExtra(EXTRA_GROUP, item.group)
    }
}
