package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val auth = AuthRepository((application as LumenApp).http, prefs)
        val field = findViewById<EditText>(R.id.playlistUrl)
        val message = findViewById<TextView>(R.id.message)
        field.setText(prefs.playlistUrl)

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = field.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                message.text = "Inserisci un indirizzo che inizia con http:// o https://"
                return@setOnClickListener
            }
            val wasEmpty = prefs.playlistUrl.isBlank()
            prefs.playlistUrl = url
            if (wasEmpty) startActivity(Intent(this, HomeActivity::class.java))
            finish()
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
