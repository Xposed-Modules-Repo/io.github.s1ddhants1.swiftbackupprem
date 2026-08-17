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
fun Step4AndroidOAuth(
    prefs: PreferencesManager,
    onCopyPackageName: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onOpenCloudConsole: () -> Unit,
    onEnableDriveApi: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCopyPackageName,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_copy_package), fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = true)
            }
            OutlinedButton(
                onClick = onCopyFingerprint,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_copy_fingerprint), fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = true)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenCloudConsole,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_cloud_console), fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = true)
            }
            Button(
                onClick = onEnableDriveApi,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_enable_drive_api), fontSize = 12.sp, textAlign = TextAlign.Center, softWrap = true)
            }
        }

        SettingsTextField(
            label = stringResource(R.string.label_client_id),
            pref = prefs.clientId,
            onPrefChange = { prefs.clientId = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            )
        )

        WizardNavRow(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.btn_review))
    }
}
