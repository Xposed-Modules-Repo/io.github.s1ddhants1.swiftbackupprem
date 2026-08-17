package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
        Button(
            onClick = onOpenConsole,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.btn_open_console), textAlign = TextAlign.Center, softWrap = true)
        }

        SettingsTextField(
            label = stringResource(R.string.label_google_app_id),
            pref = prefs.googleAppId,
            onPrefChange = { prefs.googleAppId = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            )
        )

        SettingsTextField(
            label = stringResource(R.string.label_google_api_key),
            pref = prefs.googleApiKey,
            onPrefChange = { prefs.googleApiKey = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            )
        )

        SettingsTextField(
            label = stringResource(R.string.label_gcm_sender_id),
            pref = prefs.gcmDefaultSenderId,
            onPrefChange = { prefs.gcmDefaultSenderId = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            )
        )

        SettingsTextField(
            label = stringResource(R.string.label_google_storage_bucket),
            pref = prefs.googleStorageBucket,
            onPrefChange = { prefs.googleStorageBucket = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            )
        )

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}
