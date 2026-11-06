package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs
import app.lumen.tv.data.Updater
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val app = application as LumenApp
        val auth = AuthRepository(app.http, Prefs(this))
        val username = findViewById<EditText>(R.id.username)
        val password = findViewById<EditText>(R.id.password)
        val message = findViewById<TextView>(R.id.message)
        val button = findViewById<Button>(R.id.signIn)
        val toggle = findViewById<Button>(R.id.showPassword)
        username.requestFocus()

        var visible = false
        toggle.setOnClickListener {
            visible = !visible
            password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.setSelection(password.text.length)
            toggle.text = if (visible) "Nascondi password" else "Mostra password"
        }

        button.setOnClickListener {
            // Le tastiere delle TV aggiungono spesso uno spazio finale dopo un suggerimento.
            val name = username.text.toString().trim()
            val secret = password.text.toString().trim()
            if (name.isEmpty() || secret.isEmpty()) {
                message.text = "Inserisci username e password"
                return@setOnClickListener
            }
            button.isEnabled = false
            message.text = "Accesso in corso…"
            lifecycleScope.launch {
                try {
                    auth.signIn(name, secret)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    message.text = e.message ?: "Accesso non riuscito"
                    button.isEnabled = true
                }
            }
        }

        // Gli aggiornamenti si possono installare anche prima di accedere.
        val updater = Updater(this, app.http)
        lifecycleScope.launch {
            runCatching { updater.check() }.getOrNull()?.let { promptUpdate(it, updater) }
        }
    }
}
