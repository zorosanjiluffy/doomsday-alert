import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase settings come from app/google-services.json (downloaded from the Firebase console,
// never committed). CI writes that file from the GOOGLE_SERVICES_JSON secret.
// We read it ourselves instead of using the google-services plugin, so the app still builds
// without it (push is just disabled).
data class FirebaseCfg(val apiKey: String, val appId: String, val projectId: String, val senderId: String)

fun readFirebase(): FirebaseCfg {
    val f = file("google-services.json")
    if (!f.exists()) return FirebaseCfg("", "", "", "")
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(f) as Map<String, Any?>
    val info = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    val client = clients.firstOrNull {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "com.doomsdaywatch.alert"
    } ?: clients.first()
    val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String
    val apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"] as String
    return FirebaseCfg(apiKey, appId, info["project_id"] as String, info["project_number"] as String)
}

val fb = readFirebase()
val statusUrl: String = (project.findProperty("statusUrl") as String?)
    ?: System.getenv("STATUS_URL")?.takeIf { it.isNotBlank() }
    ?: "https://doomsday-alert-production.up.railway.app/status"

android {
    namespace = "com.doomsdaywatch.alert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.doomsdaywatch.alert"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        buildConfigField("String", "FB_API_KEY", "\"${fb.apiKey}\"")
        buildConfigField("String", "FB_APP_ID", "\"${fb.appId}\"")
        buildConfigField("String", "FB_PROJECT_ID", "\"${fb.projectId}\"")
        buildConfigField("String", "FB_SENDER_ID", "\"${fb.senderId}\"")
        buildConfigField("String", "FCM_TOPIC", "\"doomsday-chennai\"")
        buildConfigField("String", "STATUS_URL", "\"$statusUrl\"")
        buildConfigField("String", "DISTRICT_URL", "\"https://www.district.in/movies/avengers-doomsday-movie-tickets-in-chennai-MV176735\"")
    }

    signingConfigs {
        // A fixed key so friends can install updates over the old version.
        // Generate once with:  keytool -genkeypair -keystore app/doomsday.jks -alias doomsday ...
        create("release") {
            val ks = file(System.getenv("KEYSTORE_PATH") ?: "doomsday.jks")
            val pwFile = file("keystore.password")
            val pw = System.getenv("KEYSTORE_PASSWORD") ?: if (pwFile.exists()) pwFile.readText().trim() else ""
            if (ks.exists() && pw.isNotEmpty()) {
                storeFile = ks
                storePassword = pw
                keyAlias = "doomsday"
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
