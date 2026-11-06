package app.lumen.tv.data

import app.lumen.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Legge la stessa configurazione pubblica usata da Lumen per iPhone. */
object RemoteConfig {
    suspend fun tmdbKey(http: OkHttpClient, prefs: Prefs): String? = withContext(Dispatchers.IO) {
        val fetched = runCatching {
            val request = Request.Builder().url("${BuildConfig.CONFIG_URL}?t=${System.currentTimeMillis()}").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty())
            }
        }.getOrNull()
        if (fetched != null) prefs.tmdbKey = fetched
        fetched ?: prefs.tmdbKey.takeIf { it.isNotBlank() }
    }

    private fun parse(json: String): String? {
        val providers = JSONObject(json).optJSONArray("providers") ?: return null
        for (i in 0 until providers.length()) {
            val provider = providers.getJSONObject(i)
            if (provider.optString("type") == "tmdb") {
                return provider.optJSONObject("options")?.optString("tmdbKey")?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
