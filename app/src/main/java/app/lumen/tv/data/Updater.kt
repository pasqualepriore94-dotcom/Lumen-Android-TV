package app.lumen.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.lumen.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(val versionCode: Int, val versionName: String, val url: String)

/** Controlla, scarica e fa installare le nuove versioni pubblicate dalla compilazione su GitHub. */
class Updater(context: Context, private val http: OkHttpClient) {
    private val appContext = context.applicationContext

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${BuildConfig.UPDATE_URL}?t=${System.currentTimeMillis()}").build()
        http.newCall(request).execute().use { response ->
            // Finché non è stata pubblicata alcuna versione il file non esiste.
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            val info = UpdateInfo(json.getInt("versionCode"), json.getString("versionName"), json.getString("url"))
            info.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    suspend fun download(info: UpdateInfo, onProgress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val base = if (Build.VERSION.SDK_INT >= 24) appContext.cacheDir else appContext.externalCacheDir ?: appContext.cacheDir
        val dir = File(base, "updates").apply { mkdirs() }
        val file = File(dir, "Lumen-TV.apk")
        http.newCall(Request.Builder().url(info.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Il server ha risposto ${response.code}")
            val body = response.body ?: throw IOException("Risposta vuota")
            val total = body.contentLength()
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        file
    }

    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= 26 && !appContext.packageManager.canRequestPackageInstalls()

    fun installIntent(file: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
        } else {
            Uri.fromFile(file)
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
