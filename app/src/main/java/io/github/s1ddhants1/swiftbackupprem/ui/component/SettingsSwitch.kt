package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsSwitch(
    label: String,
    secondaryLabel: String,
    pref: Boolean,
    enabled: Boolean = true,
    onPrefChange: (Boolean) -> Unit,
) {
    val titleAlpha = if (enabled) 1f else 0.38f
    val subtitleAlpha = if (enabled) 0.6f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(enabled = enabled) { onPrefChange(!pref) },
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(0.95f, true)
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = titleAlpha)
                )
            ) {
                Text(text = label, softWrap = true)
            }
            ProvideTextStyle(
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = subtitleAlpha)
                )
            ) {
                Text(text = secondaryLabel)
            }
        }

        Spacer(Modifier.weight(0.05f, true))

        Switch(
            checked = if (enabled) pref else false,
            enabled = enabled,
            onCheckedChange = onPrefChange
        )
    }
}
