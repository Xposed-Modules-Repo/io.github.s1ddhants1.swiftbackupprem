package io.github.s1ddhants1.swiftbackupprem.util

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.core.content.edit
import io.github.s1ddhants1.swiftbackupprem.Consts
import kotlin.reflect.KProperty

@Stable
class PreferencesManager(
    private val prefs: SharedPreferences?,
    private val isDynamic: Boolean = false
) {
    private class Preference<T>(
        private val isDynamic: Boolean,
        private val key: String,
        private val defaultValue: T,
        private val getter: (key: String, defaultValue: T) -> T,
        private val setter: (key: String, newValue: T) -> Unit
    ) {
        var value by mutableStateOf(getter(key, defaultValue))
            private set

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            return if (isDynamic) {
                getter(key, defaultValue)
            } else {
                value
            }
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
            value = newValue
            setter(key, newValue)
        }
    }

    private fun getString(key: String, defaultValue: String) = prefs?.getString(key, defaultValue) ?: defaultValue
    private fun getBoolean(key: String, defaultValue: Boolean) = prefs?.getBoolean(key, defaultValue) ?: defaultValue

    private fun putString(key: String, value: String?) {
        attempt("save preference string $key", silent = true) {
            prefs?.edit(commit = true) { putString(key, value) }
        }
    }
    private fun putBoolean(key: String, value: Boolean) {
        attempt("save preference boolean $key", silent = true) {
            prefs?.edit(commit = true) { putBoolean(key, value) }
        }
    }

    private fun stringPreference(
        key: String
    ) = Preference(
        isDynamic = isDynamic,
        key = key,
        defaultValue = "",
        getter = ::getString,
        setter = ::putString
    )

    private fun booleanPreference(
        key: String,
        defaultValue: Boolean = false
    ) = Preference(
        isDynamic = isDynamic,
        key = key,
        defaultValue = defaultValue,
        getter = ::getBoolean,
        setter = ::putBoolean
    )

    var googleAppId by stringPreference(Consts.googleAppId)
    var googleApiKey by stringPreference(Consts.googleApiKey)
    var firebaseDatabaseUrl by stringPreference(Consts.firebaseDatabaseUrl)
    var gcmDefaultSenderId by stringPreference(Consts.gcmDefaultSenderId)
    var googleStorageBucket by stringPreference(Consts.googleStorageBucket)
    var projectId by stringPreference(Consts.projectId)
    var clientId by stringPreference(Consts.oauthClientId)

    var enablePremium by booleanPreference("enable_premium", true)

    var disableTelemetry by booleanPreference("disable_telemetry", true)

    var enableDriveDiscovery by booleanPreference("enable_drive_discovery", false)

    var customFirebaseApp by booleanPreference("custom_firebase_app")

    fun toConfig(): io.github.s1ddhants1.swiftbackupprem.model.SbpConfig = io.github.s1ddhants1.swiftbackupprem.model.SbpConfig(
        enablePremium = enablePremium,
        disableTelemetry = disableTelemetry,
        enableDriveDiscovery = enableDriveDiscovery,
        customFirebaseApp = customFirebaseApp,
        googleAppId = googleAppId,
        googleApiKey = googleApiKey,
        firebaseDatabaseUrl = firebaseDatabaseUrl,
        gcmDefaultSenderId = gcmDefaultSenderId,
        googleStorageBucket = googleStorageBucket,
        projectId = projectId,
        clientId = clientId
    )

    fun applyConfig(config: io.github.s1ddhants1.swiftbackupprem.model.SbpConfig) {
        enablePremium = config.enablePremium
        disableTelemetry = config.disableTelemetry
        enableDriveDiscovery = config.enableDriveDiscovery
        customFirebaseApp = config.customFirebaseApp
        googleAppId = config.googleAppId
        googleApiKey = config.googleApiKey
        firebaseDatabaseUrl = config.firebaseDatabaseUrl
        gcmDefaultSenderId = config.gcmDefaultSenderId
        googleStorageBucket = config.googleStorageBucket
        projectId = config.projectId
        clientId = config.clientId
    }
}
