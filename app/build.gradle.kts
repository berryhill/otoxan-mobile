plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultXanderVoiceEndpoint = "http://100.126.0.110:8787/voice-turn"
val defaultXanderVoiceConnectTimeoutMillis = 10_000
val defaultXanderVoiceReadTimeoutMillis = 60_000
val defaultXanderVoiceMetricsTimeoutMillis = 5_000

val xanderVoiceEndpoint: String = providers.gradleProperty("XANDER_VOICE_ENDPOINT")
    .orElse(providers.environmentVariable("XANDER_VOICE_ENDPOINT"))
    .orElse(defaultXanderVoiceEndpoint)
    .get()

fun endpointPolicyInt(name: String, defaultValue: Int): Int = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .map { value -> value.toInt() }
    .orElse(defaultValue)
    .get()

val xanderVoiceConnectTimeoutMillis = endpointPolicyInt(
    "XANDER_VOICE_CONNECT_TIMEOUT_MILLIS",
    defaultXanderVoiceConnectTimeoutMillis
)
val xanderVoiceReadTimeoutMillis = endpointPolicyInt(
    "XANDER_VOICE_READ_TIMEOUT_MILLIS",
    defaultXanderVoiceReadTimeoutMillis
)
val xanderVoiceMetricsTimeoutMillis = endpointPolicyInt(
    "XANDER_VOICE_METRICS_TIMEOUT_MILLIS",
    defaultXanderVoiceMetricsTimeoutMillis
)

android {
    namespace = "com.otoxan.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.otoxan.mobile"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "XANDER_VOICE_ENDPOINT", "\"${xanderVoiceEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("int", "XANDER_VOICE_CONNECT_TIMEOUT_MILLIS", xanderVoiceConnectTimeoutMillis.toString())
        buildConfigField("int", "XANDER_VOICE_READ_TIMEOUT_MILLIS", xanderVoiceReadTimeoutMillis.toString())
        buildConfigField("int", "XANDER_VOICE_METRICS_TIMEOUT_MILLIS", xanderVoiceMetricsTimeoutMillis.toString())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
