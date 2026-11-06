# Lumen-Android-TV

Versione di Lumen per TV Android e Google TV, leggera e pensata per il telecomando.
Richiede Android 6 o successivo. Non dipende dai servizi Google.

## Compilazione

Ogni push su `main` o su un ramo `tappa-*` compila l'APK con GitHub Actions
(artefatto `Lumen-TV-apk`).

Secret del repository, tutti facoltativi:

| Secret | Uso |
| --- | --- |
| `LUMEN_KEYSTORE_B64`, `LUMEN_KEYSTORE_PASSWORD` | Firma stabile, per aggiornare senza disinstallare |
| `TMDB_API_KEY` | Locandine e trame da TMDB |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | Accesso con account |
