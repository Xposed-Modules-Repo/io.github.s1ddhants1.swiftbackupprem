package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
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
fun Step2Database(
    prefs: PreferencesManager,
    onOpenConsole: () -> Unit,
    onCopyRules: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WizardActionButton(
                text = stringResource(R.string.btn_open_console),
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
            WizardActionButton(
                text = stringResource(R.string.btn_copy_rules),
                onClick = onCopyRules,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ContentCopy,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        SettingsTextField(
            label = stringResource(R.string.label_project_id),
            pref = prefs.projectId,
            onPrefChange = { prefs.projectId = it }
        )

        SettingsTextField(
            label = stringResource(R.string.label_firebase_db_url),
            pref = prefs.firebaseDatabaseUrl,
            onPrefChange = { prefs.firebaseDatabaseUrl = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        )

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}
