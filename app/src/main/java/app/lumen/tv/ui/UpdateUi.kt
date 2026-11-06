package app.lumen.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.lumen.tv.data.UpdateInfo
import app.lumen.tv.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var updateDialogOpen = false
private var lastCheck = 0L

/** Controlla gli aggiornamenti a ogni ritorno nell'app (al massimo ogni 30 secondi). */
fun ComponentActivity.checkForUpdate(updater: Updater) {
    val now = System.currentTimeMillis()
    if (updateDialogOpen || now - lastCheck < 30_000) return
    lastCheck = now
    lifecycleScope.launch {
        runCatching { updater.check() }.getOrNull()?.let { promptUpdate(it, updater) }
    }
}

fun ComponentActivity.promptUpdate(info: UpdateInfo, updater: Updater) {
    if (isFinishing || updateDialogOpen) return
    updateDialogOpen = true
    AlertDialog.Builder(this)
        .setOnDismissListener { updateDialogOpen = false }
        .setTitle("Aggiornamento disponibile")
        .setMessage("È disponibile Lumen ${info.versionName}. Vuoi installarlo ora?")
        .setPositiveButton("Aggiorna") { _, _ -> runUpdate(info, updater) }
        .setNegativeButton("Più tardi", null)
        .show()
}

private fun ComponentActivity.runUpdate(info: UpdateInfo, updater: Updater) {
    if (updater.needsInstallPermission()) {
        askInstallPermission()
        return
    }
    val progress = AlertDialog.Builder(this)
        .setTitle("Aggiornamento")
        .setMessage("Download in corso…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        try {
            val file = updater.download(info) { percent ->
                withContext(Dispatchers.Main) { progress.setMessage("Download in corso: $percent%") }
            }
            progress.dismiss()
            startActivity(updater.installIntent(file))
        } catch (e: Exception) {
            progress.dismiss()
            AlertDialog.Builder(this@runUpdate)
                .setTitle("Aggiornamento non riuscito")
                .setMessage(e.message ?: "Riprova più tardi.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

private fun ComponentActivity.askInstallPermission() {
    AlertDialog.Builder(this)
        .setTitle("Serve un'autorizzazione")
        .setMessage("Consenti a Lumen di installare app, poi torna qui e cerca di nuovo l'aggiornamento in Impostazioni.")
        .setPositiveButton("Apri impostazioni") { _, _ ->
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            }
        }
        .setNegativeButton("Annulla", null)
        .show()
}
