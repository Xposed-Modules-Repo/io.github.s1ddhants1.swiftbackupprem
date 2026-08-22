package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun Step5ReviewFinish(
    prefs: PreferencesManager,
    onImportClick: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val googleAppIdLabel = stringResource(R.string.label_google_app_id)
    val googleApiKeyLabel = stringResource(R.string.label_google_api_key)
    val firebaseDbUrlLabel = stringResource(R.string.label_firebase_db_url)
    val gcmSenderIdLabel = stringResource(R.string.label_gcm_sender_id)
    val projectIdLabel = stringResource(R.string.label_project_id)
    val clientIdLabel = stringResource(R.string.label_client_id)

    val requiredFields = remember(
        prefs.googleAppId, prefs.googleApiKey, prefs.firebaseDatabaseUrl,
        prefs.gcmDefaultSenderId, prefs.projectId, prefs.clientId,
        googleAppIdLabel, googleApiKeyLabel, firebaseDbUrlLabel,
        gcmSenderIdLabel, projectIdLabel, clientIdLabel
    ) {
        listOf(
            googleAppIdLabel to prefs.googleAppId,
            googleApiKeyLabel to prefs.googleApiKey,
            firebaseDbUrlLabel to prefs.firebaseDatabaseUrl,
            gcmSenderIdLabel to prefs.gcmDefaultSenderId,
            projectIdLabel to prefs.projectId,
            clientIdLabel to prefs.clientId
        )
    }

    val allFilled = requiredFields.all { it.second.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allFilled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (allFilled) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = null,
                    tint = if (allFilled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(if (allFilled) R.string.wizard_credentials_complete else R.string.wizard_checklist_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                requiredFields.forEach { (label, value) ->
                    val ok = value.isNotBlank()
                    ReviewStatusRow(
                        label = label,
                        statusText = stringResource(if (ok) R.string.wizard_status_ok else R.string.wizard_status_missing),
                        statusColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        icon = if (ok) Icons.Default.CheckCircle else Icons.Default.Error
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                val hasBucket = prefs.googleStorageBucket.isNotBlank()
                ReviewStatusRow(
                    label = stringResource(R.string.wizard_storage_bucket_optional),
                    statusText = stringResource(if (hasBucket) R.string.wizard_status_ok else R.string.wizard_status_auto_default),
                    statusColor = if (hasBucket) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    icon = if (hasBucket) Icons.Default.CheckCircle else Icons.Default.AutoAwesome
                )
            }
        }

        WizardActionButton(
            text = stringResource(R.string.btn_import_json),
            onClick = onImportClick,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.UploadFile
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WizardActionButton(
                text = stringResource(R.string.btn_back),
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
            WizardActionButton(
                text = stringResource(R.string.btn_finish_save),
                onClick = onFinish,
                icon = Icons.Default.Save,
                isPrimary = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ReviewStatusRow(
    label: String,
    statusText: String,
    statusColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = statusColor)
        }
    }
}
