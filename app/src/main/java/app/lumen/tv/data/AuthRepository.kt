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

/**
 * Accesso email e password tramite Supabase Auth.
 * Finché SUPABASE_URL e SUPABASE_ANON_KEY non sono impostati, l'accesso è disattivato.
 */
class AuthRepository(private val http: OkHttpClient, private val prefs: Prefs) {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val isSignedIn: Boolean get() = prefs.accessToken.isNotBlank()

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=password")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 401) throw IOException("Email o password non corrette")
            if (!response.isSuccessful) throw IOException("Il server ha risposto ${response.code}")
            prefs.accessToken = JSONObject(response.body?.string().orEmpty()).getString("access_token")
        }
    }

    fun signOut() {
        prefs.accessToken = ""
    }
}
