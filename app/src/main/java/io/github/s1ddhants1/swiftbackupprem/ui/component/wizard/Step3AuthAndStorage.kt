package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.ui.component.SettingsTextField
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun Step3AuthAndStorage(
    prefs: PreferencesManager,
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardActionButton(
            text = stringResource(R.string.btn_open_console),
            onClick = onOpenConsole,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.AutoMirrored.Filled.Launch,
            isPrimary = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        )

        SettingsTextField(
            label = stringResource(R.string.label_google_app_id),
            pref = prefs.googleAppId,
            onPrefChange = { prefs.googleAppId = it }
        )

        SettingsTextField(
            label = stringResource(R.string.label_google_api_key),
            pref = prefs.googleApiKey,
            onPrefChange = { prefs.googleApiKey = it }
        )

        SettingsTextField(
            label = stringResource(R.string.label_gcm_sender_id),
            pref = prefs.gcmDefaultSenderId,
            onPrefChange = { prefs.gcmDefaultSenderId = it },
            keyboardType = KeyboardType.Number
        )

        SettingsTextField(
            label = stringResource(R.string.label_google_storage_bucket),
            pref = prefs.googleStorageBucket,
            onPrefChange = { prefs.googleStorageBucket = it },
            imeAction = ImeAction.Done
        )

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}
