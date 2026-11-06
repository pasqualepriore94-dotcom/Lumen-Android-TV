package app.lumen.tv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.lumen.tv.LumenApp
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs

/** Sceglie la prima schermata: accesso oppure Home. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val auth = AuthRepository((application as LumenApp).http, prefs)
        val next = when {
            !auth.isSignedIn -> LoginActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
