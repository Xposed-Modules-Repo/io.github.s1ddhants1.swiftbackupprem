package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
            WizardActionButton(
                text = stringResource(R.string.btn_copy_package),
                onClick = onCopyPackageName,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ContentCopy,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
            WizardActionButton(
                text = stringResource(R.string.btn_copy_fingerprint),
                onClick = onCopyFingerprint,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ContentCopy,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WizardActionButton(
                text = stringResource(R.string.btn_cloud_console),
                onClick = onOpenCloudConsole,
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
            WizardActionButton(
                text = stringResource(R.string.btn_enable_drive_api),
                onClick = onEnableDriveApi,
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Filled.Launch,
                isPrimary = true,
                fontSize = 12.sp,
                iconSize = 14.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        SettingsTextField(
            label = stringResource(R.string.label_client_id),
            pref = prefs.clientId,
            onPrefChange = { prefs.clientId = it },
            imeAction = ImeAction.Done
        )

        WizardNavRow(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.btn_review))
    }
}
