plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
fun ensureModuleGoogleServicesJson() {
    val targetFile = file("google-services.json")
    if (targetFile.isFile) return

    val rootGs = rootProject.file("google-services.json")
    if (rootGs.isFile) {
        rootGs.copyTo(targetFile, overwrite = true)
        return
    }

    val appletCfg = rootProject.file("firebase-applet-config.json")
    if (!appletCfg.isFile) return
    val text = appletCfg.readText(Charsets.UTF_8)
    fun field(k: String): String =
        Regex("\"${Regex.escape(k)}\"\\s*:\\s*\"([^\"]+)\"")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    val projectId = field("projectId")
    val apiKey = field("apiKey")
    if (projectId.isEmpty() || apiKey.isEmpty()) return

    val projectNumber = field("messagingSenderId").ifEmpty { "000000000000" }
    val storageBucket = field("storageBucket").ifEmpty { "${projectId}.appspot.com" }
    val oauthClientId = field("oAuthClientId")
    val appIdRaw = field("appId")
    val hexTail = Regex(":([0-9a-fA-F]{8,})$").find(appIdRaw)?.groupValues?.getOrNull(1)?.lowercase()
        ?: "9a6d13baf4427440"
    val prefix14 = hexTail.take(14).padEnd(14, '0')
    val oauthJson = if (oauthClientId.isNotEmpty()) {
        "[{\"client_id\":\"${oauthClientId}\",\"client_type\":3}]"
    } else {
        "[]"
    }

    fun clientBlock(pkg: String, suffix: String): String = """
    {
      "client_info": {
        "mobilesdk_app_id": "1:${projectNumber}:android:${prefix14}${suffix}",
        "android_client_info": { "package_name": "${pkg}" }
      },
      "oauth_client": ${oauthJson},
      "api_key": [{ "current_key": "${apiKey}" }],
      "services": {
        "appinvite_service": { "other_platform_oauth_client": ${oauthJson} }
      }
    }""".trimIndent()

    val synthesized = """
{
  "project_info": {
    "project_number": "${projectNumber}",
    "project_id": "${projectId}",
    "storage_bucket": "${storageBucket}"
  },
  "client": [
${clientBlock("app.backup.source", "01")},
${clientBlock("app.backup.viewer", "02")}
  ],
  "configuration_version": "1"
}
""".trimIndent() + "\n"

    targetFile.writeText(synthesized, Charsets.UTF_8)
}

ensureModuleGoogleServicesJson()
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "app.backup.source"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.backup.source"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
