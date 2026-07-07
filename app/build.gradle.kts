plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultXanderVoiceEndpoint = "http://100.126.0.110:8787/voice-turn"
val defaultXanderVoiceConnectTimeoutMillis = 10_000
val defaultXanderVoiceReadTimeoutMillis = 60_000
val defaultXanderVoiceMetricsTimeoutMillis = 5_000
val defaultOtoxanStreamingVoiceClientEnabled = false
val defaultOtoxanStreamingVoiceEndpoint = ""
val defaultOtoxanDiagnosticPcmChunksEnabled = false
val defaultOtoxanDiagnosticPcmEndpoint = ""
val defaultOtoxanDiagnosticPcmChunkBytes = 3_200
val defaultOtoxanDiagnosticPcmTimeoutMillis = 2_000
val defaultConversationCaptureTuningEvidenceGate = false
val defaultConversationCaptureMaxMillis = 12_000
val defaultConversationCaptureMinMillis = 700
val defaultConversationCaptureSilenceAfterSpeechMillis = 450
val defaultConversationCaptureSpeechPeakAmplitude = 900
val defaultConversationCaptureChunkMillis = 100

val xanderVoiceEndpoint: String = providers.gradleProperty("XANDER_VOICE_ENDPOINT")
    .orElse(providers.environmentVariable("XANDER_VOICE_ENDPOINT"))
    .orElse(defaultXanderVoiceEndpoint)
    .get()

fun endpointPolicyInt(name: String, defaultValue: Int): Int = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .map { value -> value.toInt() }
    .orElse(defaultValue)
    .get()

fun policyBoolean(name: String, defaultValue: Boolean): Boolean = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .map { value -> value.toBooleanStrict() }
    .orElse(defaultValue)
    .get()

fun boundedPolicyInt(name: String, defaultValue: Int, range: IntRange): Int {
    val value = endpointPolicyInt(name, defaultValue)
    require(value in range) { "$name must be in ${range.first}..${range.last}; got $value" }
    return value
}

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
val otoxanStreamingVoiceClientEnabled = policyBoolean(
    "OTOXAN_STREAMING_VOICE_CLIENT_ENABLED",
    defaultOtoxanStreamingVoiceClientEnabled
)
val otoxanStreamingVoiceEndpoint: String = providers.gradleProperty("OTOXAN_STREAMING_VOICE_ENDPOINT")
    .orElse(providers.environmentVariable("OTOXAN_STREAMING_VOICE_ENDPOINT"))
    .orElse(defaultOtoxanStreamingVoiceEndpoint)
    .get()
val otoxanDiagnosticPcmChunksEnabled = policyBoolean(
    "OTOXAN_DIAGNOSTIC_PCM_CHUNKS_ENABLED",
    defaultOtoxanDiagnosticPcmChunksEnabled
)
val otoxanDiagnosticPcmEndpoint: String = providers.gradleProperty("OTOXAN_DIAGNOSTIC_PCM_ENDPOINT")
    .orElse(providers.environmentVariable("OTOXAN_DIAGNOSTIC_PCM_ENDPOINT"))
    .orElse(defaultOtoxanDiagnosticPcmEndpoint)
    .get()
val otoxanDiagnosticPcmChunkBytes = boundedPolicyInt(
    "OTOXAN_DIAGNOSTIC_PCM_CHUNK_BYTES",
    defaultOtoxanDiagnosticPcmChunkBytes,
    320..32_000
)
val otoxanDiagnosticPcmTimeoutMillis = boundedPolicyInt(
    "OTOXAN_DIAGNOSTIC_PCM_TIMEOUT_MILLIS",
    defaultOtoxanDiagnosticPcmTimeoutMillis,
    250..10_000
)
val conversationCaptureTuningEvidenceGate = policyBoolean(
    "OTOXAN_CONVERSATION_CAPTURE_TUNING_EVIDENCE_GATE",
    defaultConversationCaptureTuningEvidenceGate
)
val conversationCaptureMaxMillis = boundedPolicyInt(
    "OTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS",
    defaultConversationCaptureMaxMillis,
    5_000..20_000
)
val conversationCaptureMinMillis = boundedPolicyInt(
    "OTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS",
    defaultConversationCaptureMinMillis,
    300..2_000
)
val conversationCaptureSilenceAfterSpeechMillis = boundedPolicyInt(
    "OTOXAN_CONVERSATION_CAPTURE_SILENCE_AFTER_SPEECH_MILLIS",
    defaultConversationCaptureSilenceAfterSpeechMillis,
    250..2_000
)
val conversationCaptureSpeechPeakAmplitude = boundedPolicyInt(
    "OTOXAN_CONVERSATION_CAPTURE_SPEECH_PEAK_AMPLITUDE",
    defaultConversationCaptureSpeechPeakAmplitude,
    256..5_000
)
val conversationCaptureChunkMillis = boundedPolicyInt(
    "OTOXAN_CONVERSATION_CAPTURE_CHUNK_MILLIS",
    defaultConversationCaptureChunkMillis,
    50..200
)
require(conversationCaptureMaxMillis >= conversationCaptureMinMillis) {
    "OTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS must be >= OTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS"
}

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
        buildConfigField("boolean", "OTOXAN_STREAMING_VOICE_CLIENT_ENABLED", otoxanStreamingVoiceClientEnabled.toString())
        buildConfigField("String", "OTOXAN_STREAMING_VOICE_ENDPOINT", "\"${otoxanStreamingVoiceEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "OTOXAN_DIAGNOSTIC_PCM_CHUNKS_ENABLED", otoxanDiagnosticPcmChunksEnabled.toString())
        buildConfigField("String", "OTOXAN_DIAGNOSTIC_PCM_ENDPOINT", "\"${otoxanDiagnosticPcmEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("int", "OTOXAN_DIAGNOSTIC_PCM_CHUNK_BYTES", otoxanDiagnosticPcmChunkBytes.toString())
        buildConfigField("int", "OTOXAN_DIAGNOSTIC_PCM_TIMEOUT_MILLIS", otoxanDiagnosticPcmTimeoutMillis.toString())
        buildConfigField("boolean", "OTOXAN_CONVERSATION_CAPTURE_TUNING_EVIDENCE_GATE", conversationCaptureTuningEvidenceGate.toString())
        buildConfigField("int", "OTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS", conversationCaptureMaxMillis.toString())
        buildConfigField("int", "OTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS", conversationCaptureMinMillis.toString())
        buildConfigField("int", "OTOXAN_CONVERSATION_CAPTURE_SILENCE_AFTER_SPEECH_MILLIS", conversationCaptureSilenceAfterSpeechMillis.toString())
        buildConfigField("int", "OTOXAN_CONVERSATION_CAPTURE_SPEECH_PEAK_AMPLITUDE", conversationCaptureSpeechPeakAmplitude.toString())
        buildConfigField("int", "OTOXAN_CONVERSATION_CAPTURE_CHUNK_MILLIS", conversationCaptureChunkMillis.toString())
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
