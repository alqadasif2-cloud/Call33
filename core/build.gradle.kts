plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun readFirstExistingText(paths: List<String>): String =
    paths.asSequence()
        .map { rootProject.file(it) }
        .firstOrNull { it.isFile }
        ?.readText(Charsets.UTF_8)
        .orEmpty()

fun extractJsonField(json: String, key: String): String? =
    Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun asBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appletJson = readFirstExistingText(listOf("firebase-applet-config.json"))
val googleServicesJson = readFirstExistingText(
    listOf(
        "google-services.json",
        "backup-app/google-services.json",
        "viewer-app/google-services.json",
    )
)
val firebaseRcJson = readFirstExistingText(listOf(".firebaserc"))

val resolvedProjectId = extractJsonField(googleServicesJson, "project_id")
    ?: extractJsonField(appletJson, "projectId")
    ?: extractJsonField(firebaseRcJson, "default")
    ?: ""

val resolvedApiKey = extractJsonField(googleServicesJson, "current_key")
    ?: extractJsonField(appletJson, "apiKey")
    ?: System.getenv("FIREBASE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: ""

val resolvedSenderId = extractJsonField(googleServicesJson, "project_number")
    ?: extractJsonField(appletJson, "messagingSenderId")
    ?: ""

val resolvedStorageBucket = extractJsonField(googleServicesJson, "storage_bucket")
    ?: extractJsonField(appletJson, "storageBucket")
    ?: (if (resolvedProjectId.isNotEmpty()) "${resolvedProjectId}.firebasestorage.app" else "")

val rawWebAppId = extractJsonField(appletJson, "appId").orEmpty()
val appHexTail = Regex(":([0-9a-fA-F]{8,})$").find(rawWebAppId)?.groupValues?.getOrNull(1)?.lowercase()
    ?: "9a6d13baf4427440"
val synthesizedAndroidAppId = if (resolvedSenderId.isNotEmpty()) {
    "1:${resolvedSenderId}:android:${appHexTail.take(14).padEnd(14, '0')}01"
} else {
    ""
}

val resolvedAndroidAppId = extractJsonField(googleServicesJson, "mobilesdk_app_id")
    ?: synthesizedAndroidAppId

val resolvedDatabaseId = extractJsonField(appletJson, "firestoreDatabaseId") ?: ""

android {
    namespace = "app.backup.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "FIREBASE_PROJECT_ID", asBuildConfigString(resolvedProjectId))
        buildConfigField("String", "FIREBASE_APP_ID", asBuildConfigString(resolvedAndroidAppId))
        buildConfigField("String", "FIREBASE_API_KEY", asBuildConfigString(resolvedApiKey))
        buildConfigField("String", "FIREBASE_SENDER_ID", asBuildConfigString(resolvedSenderId))
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", asBuildConfigString(resolvedStorageBucket))
        buildConfigField("String", "FIREBASE_DATABASE_ID", asBuildConfigString(resolvedDatabaseId))
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
    api(platform("com.google.firebase:firebase-bom:33.3.0"))
    api("com.google.firebase:firebase-auth")
    api("com.google.firebase:firebase-firestore")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    api(platform("androidx.compose:compose-bom:2024.09.00"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.9.2")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    api("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
