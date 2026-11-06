package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.BuildConfig
import app.lumen.tv.data.Updater
import kotlinx.coroutines.launch
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs

class SettingsActivity : ComponentActivity() {
    private var fieldRefs: Pair<EditText, Prefs>? = null

    /** Dopo l'invio dal telefono i campi mostrano i link ricevuti. */
    override fun onResume() {
        super.onResume()
        val status = (application as LumenApp).playlistStatus
        findViewById<TextView>(R.id.playlistStatus).text = if (status.isBlank()) "" else "Playlist: $status"
        fieldRefs?.let { (field, prefs) ->
            field.setText(prefs.playlistUrl)
            findViewById<EditText>(R.id.epgUrl).setText(prefs.epgUrl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val app = application as LumenApp
        val auth = AuthRepository(app.http, prefs)
        val field = findViewById<EditText>(R.id.playlistUrl)
        val message = findViewById<TextView>(R.id.message)
        field.setText(prefs.playlistUrl)
        fieldRefs = field to prefs
        val epgField = findViewById<EditText>(R.id.epgUrl)
        epgField.setText(prefs.epgUrl)

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = field.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                message.text = "Inserisci un indirizzo che inizia con http:// o https://, oppure lascia vuoto"
                return@setOnClickListener
            }
            val epg = epgField.text.toString().trim()
            if (epg.isNotEmpty() && !epg.startsWith("http://") && !epg.startsWith("https://")) {
                message.text = "L'indirizzo della guida TV deve iniziare con http:// o https://, oppure lascia vuoto"
                return@setOnClickListener
            }
            prefs.playlistUrl = url
            if (epg != prefs.epgUrl) {
                prefs.epgUrl = epg
                app.epg.invalidate()
            }
            finish()
        }

        findViewById<Button>(R.id.fromPhone).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java).putExtra(PairActivity.EXTRA_PLAYLIST, true))
        }

        val updater = Updater(this, app.http)
        findViewById<TextView>(R.id.version).text = "Lumen ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        findViewById<Button>(R.id.checkUpdate).setOnClickListener {
            message.text = "Controllo…"
            lifecycleScope.launch {
                try {
                    val info = updater.check()
                    if (info == null) message.text = "Nessun aggiornamento disponibile" else {
                        message.text = ""
                        promptUpdate(info, updater)
                    }
                } catch (e: Exception) {
                    message.text = "Non riesco a controllare gli aggiornamenti"
                }
            }
        }

        findViewById<Button>(R.id.signOut).apply {
            setOnClickListener {
                auth.signOut()
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }
}
