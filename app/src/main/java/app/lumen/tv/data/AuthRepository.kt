package app.lumen.tv.data

import app.lumen.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Stessi account dell'app iPhone, tramite la funzione Supabase lumen-auth. */
class AuthRepository(private val http: OkHttpClient, private val prefs: Prefs) {
    val isSignedIn: Boolean get() = prefs.accessToken.isNotBlank()

    suspend fun signIn(username: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("username", username.trim().lowercase())
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${BuildConfig.AUTH_URL}/login").post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                throw IOException(json?.optString("error")?.ifBlank { null } ?: "Errore del server (${response.code})")
            }
            if (json?.optBoolean("mustChangePassword") == true) {
                throw IOException("Devi prima cambiare la password dall'app iPhone.")
            }
            prefs.accessToken = json?.optString("token")?.ifBlank { null } ?: throw IOException("Risposta del server non valida")
        }
    }

    /** Falso solo se il server rifiuta la sessione: senza connessione l'accesso resta valido. */
    suspend fun isSessionValid(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.AUTH_URL}/me")
            .header("Authorization", "Bearer ${prefs.accessToken}")
            .build()
        runCatching {
            http.newCall(request).execute().use { it.code != 401 && it.code != 403 }
        }.getOrDefault(true)
    }

    fun signOut() {
        prefs.accessToken = ""
    }
}
