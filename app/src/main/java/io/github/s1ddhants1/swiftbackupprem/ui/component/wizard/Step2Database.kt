package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            Button(
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_open_console), fontSize = 13.sp, textAlign = TextAlign.Center, softWrap = true)
            }
            OutlinedButton(
                onClick = onCopyRules,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_copy_rules), fontSize = 13.sp, textAlign = TextAlign.Center, softWrap = true)
            }
        }

        SettingsTextField(
            label = stringResource(R.string.label_project_id),
            pref = prefs.projectId,
            onPrefChange = { prefs.projectId = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            )
        )

        SettingsTextField(
            label = stringResource(R.string.label_firebase_db_url),
            pref = prefs.firebaseDatabaseUrl,
            onPrefChange = { prefs.firebaseDatabaseUrl = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )
        )

        WizardNavRow(onBack = onBack, onNext = onNext)
    }
}
