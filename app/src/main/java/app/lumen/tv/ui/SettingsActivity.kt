package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val app = application as LumenApp
        val auth = AuthRepository(app.http, prefs)
        val field = findViewById<EditText>(R.id.playlistUrl)
        val message = findViewById<TextView>(R.id.message)
        field.setText(prefs.playlistUrl)

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = field.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                message.text = "Inserisci un indirizzo che inizia con http:// o https://, oppure lascia vuoto"
                return@setOnClickListener
            }
            prefs.playlistUrl = url
            finish()
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
            visibility = if (auth.isConfigured) View.VISIBLE else View.GONE
            setOnClickListener {
                auth.signOut()
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }
}
