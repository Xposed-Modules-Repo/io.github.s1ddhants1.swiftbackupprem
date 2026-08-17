package io.github.s1ddhants1.swiftbackupprem.data

import android.content.ContentResolver
import android.net.Uri
import io.github.s1ddhants1.swiftbackupprem.model.SbpConfig
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject

interface ConfigRepository {
    suspend fun exportConfig(contentResolver: ContentResolver, uri: Uri, config: SbpConfig): Result<Unit>
    suspend fun importConfig(contentResolver: ContentResolver, uri: Uri, prefs: PreferencesManager): Result<SbpConfig>
    fun parseConfig(jsonStr: String, prefs: PreferencesManager): SbpConfig
}

class ConfigRepositoryImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) : ConfigRepository {

    override suspend fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        config: SbpConfig
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val jsonString = json.encodeToString(SbpConfig.serializer(), config)
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open selected export destination")
        }
    }

    override suspend fun importConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        prefs: PreferencesManager
    ): Result<SbpConfig> = withContext(ioDispatcher) {
        runCatching {
            val jsonStr = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: error("Could not open selected import file")

            val parsedConfig = parseConfig(jsonStr, prefs)
            parsedConfig
        }
    }

    override fun parseConfig(jsonStr: String, prefs: PreferencesManager): SbpConfig {
        val rawJson = JSONObject(jsonStr)
        val isGoogleServices = rawJson.has("client") && rawJson.has("project_info")
        val hasSbpKeys = rawJson.has("enablePremium") ||
                rawJson.has("disableTelemetry") ||
                rawJson.has("suppressTelemetry") ||
                rawJson.has("customFirebaseApp") ||
                rawJson.has("googleAppId") ||
                rawJson.has("projectId")

        if (!isGoogleServices && !hasSbpKeys) {
            throw IllegalArgumentException("Unrecognized or invalid configuration file format")
        }

        if (isGoogleServices) {
            prefs.customFirebaseApp = true
            GoogleServicesJson.applyToPrefs(rawJson, prefs)
            return prefs.toConfig()
        }

        // Try kotlinx.serialization first
        val baseConfig = runCatching {
            json.decodeFromString(SbpConfig.serializer(), jsonStr)
        }.getOrDefault(prefs.toConfig())

        // Handle legacy alias `suppressTelemetry` if present
        val effectiveDisableTelemetry = when {
            rawJson.has("disableTelemetry") -> rawJson.optBoolean("disableTelemetry", true)
            rawJson.has("suppressTelemetry") -> rawJson.optBoolean("suppressTelemetry", true)
            else -> baseConfig.disableTelemetry
        }

        val updatedConfig = baseConfig.copy(
            disableTelemetry = effectiveDisableTelemetry,
            enablePremium = if (rawJson.has("enablePremium")) rawJson.optBoolean("enablePremium", baseConfig.enablePremium) else baseConfig.enablePremium,
            customFirebaseApp = if (rawJson.has("customFirebaseApp")) rawJson.optBoolean("customFirebaseApp", baseConfig.customFirebaseApp) else baseConfig.customFirebaseApp,
            googleAppId = if (rawJson.has("googleAppId")) rawJson.optString("googleAppId", baseConfig.googleAppId) else baseConfig.googleAppId,
            googleApiKey = if (rawJson.has("googleApiKey")) rawJson.optString("googleApiKey", baseConfig.googleApiKey) else baseConfig.googleApiKey,
            firebaseDatabaseUrl = if (rawJson.has("firebaseDatabaseUrl")) rawJson.optString("firebaseDatabaseUrl", baseConfig.firebaseDatabaseUrl) else baseConfig.firebaseDatabaseUrl,
            gcmDefaultSenderId = if (rawJson.has("gcmDefaultSenderId")) rawJson.optString("gcmDefaultSenderId", baseConfig.gcmDefaultSenderId) else baseConfig.gcmDefaultSenderId,
            googleStorageBucket = if (rawJson.has("googleStorageBucket")) rawJson.optString("googleStorageBucket", baseConfig.googleStorageBucket) else baseConfig.googleStorageBucket,
            projectId = if (rawJson.has("projectId")) rawJson.optString("projectId", baseConfig.projectId) else baseConfig.projectId,
            clientId = if (rawJson.has("clientId")) rawJson.optString("clientId", baseConfig.clientId) else baseConfig.clientId
        )

        prefs.applyConfig(updatedConfig)
        return updatedConfig
    }
}
