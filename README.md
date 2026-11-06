# Lumen-Android-TV

Versione di Lumen per TV Android e Google TV, leggera e pensata per il telecomando.
Richiede Android 6 o successivo. Non dipende dai servizi Google.

## Compilazione

Ogni push su `main` o su un ramo `tappa-*` compila l'APK con GitHub Actions
(artefatto `Lumen-TV-apk`).

Secret del repository:

| Secret | Uso |
| --- | --- |
| `SUPABASE_SERVICE_KEY` | Pubblica APK e `version.json` nel bucket `lumen/android-tv` per gli aggiornamenti sulla TV |

La chiave TMDB arriva da `app-config.json`, lo stesso file dell'app iPhone. Gli account usano la funzione `lumen-auth`.

APK per Downloader: `https://sicpnokpgjqwnhzlznvm.supabase.co/storage/v1/object/public/lumen/android-tv/Lumen-TV.apk`
