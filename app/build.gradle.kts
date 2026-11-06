plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun secret(name: String) = (System.getenv(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "app.lumen.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lumen.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.0"
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        create("beta") {
            val path = System.getenv("LUMEN_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("LUMEN_KEYSTORE_PASSWORD")
                keyAlias = "lumen"
                keyPassword = System.getenv("LUMEN_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("LUMEN_KEYSTORE_PATH").isNullOrBlank())
                signingConfigs.getByName("debug") else signingConfigs.getByName("beta")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
