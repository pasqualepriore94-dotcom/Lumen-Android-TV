package app.lumen.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.lumen.tv.LumenApp
import app.lumen.tv.R
import app.lumen.tv.data.AuthRepository
import app.lumen.tv.data.Prefs
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mostra un QR: l'utente accede dal telefono e la TV riceve la sessione. */
class PairActivity : ComponentActivity() {
    private lateinit var auth: AuthRepository
    private lateinit var qr: ImageView
    private lateinit var code: TextView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        auth = AuthRepository((application as LumenApp).http, Prefs(this))
        qr = findViewById(R.id.qr)
        code = findViewById(R.id.code)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        findViewById<Button>(R.id.back).apply {
            requestFocus()
            setOnClickListener { finish() }
        }
        // Il controllo si ferma quando la schermata non è visibile e riparte con un codice nuovo.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { run() } }
    }

    private suspend fun run() {
        while (true) {
            progress.visibility = View.VISIBLE
            qr.setImageDrawable(null)
            code.text = ""
            val pairing = try {
                auth.startPairing()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                message.text = "Non riesco a ottenere un codice: ${e.message.orEmpty()}\nRiprovo tra poco…"
                delay(10_000)
                continue
            }
            val size = 512
            qr.setImageBitmap(withContext(Dispatchers.Default) { qrBitmap(pairing.url, size) })
            progress.visibility = View.GONE
            code.text = pairing.code.chunked(3).joinToString(" ")
            message.text = "In attesa dell'accesso dal telefono…"

            val deadline = System.currentTimeMillis() + pairing.expiresInSeconds * 1000L
            var state = AuthRepository.PairState.PENDING
            while (state == AuthRepository.PairState.PENDING && System.currentTimeMillis() < deadline) {
                delay(3_000)
                state = runCatching { auth.pollPairing(pairing) }.getOrDefault(AuthRepository.PairState.PENDING)
            }
            if (state == AuthRepository.PairState.APPROVED) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
                return
            }
            message.text = "Codice scaduto: ne genero uno nuovo…"
        }
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
