package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val auth = AuthRepository((application as LumenApp).http, Prefs(this))
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val message = findViewById<TextView>(R.id.message)
        val button = findViewById<Button>(R.id.signIn)
        email.requestFocus()
        button.setOnClickListener {
            button.isEnabled = false
            message.text = "Accesso in corso…"
            lifecycleScope.launch {
                try {
                    auth.signIn(email.text.toString().trim(), password.text.toString())
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    message.text = e.message ?: "Accesso non riuscito"
                    button.isEnabled = true
                }
            }
        }
    }
}
