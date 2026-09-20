package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.BuildConfig
import io.github.s1ddhants1.swiftbackupprem.R
import io.github.s1ddhants1.swiftbackupprem.ui.theme.AppSpacing

private data class Contributor(
    @DrawableRes val avatarRes: Int,
    val name: String,
    val role: String,
    val githubUrl: String? = null,
    val telegramUrl: String? = null
)

private val CONTRIBUTORS = listOf(
    Contributor(
        avatarRes = R.drawable.ic_avatar_s1ddhants1,
        name = "s1ddhants1",
        role = "Maintainer",
        githubUrl = "https://github.com/s1ddhants1",
        telegramUrl = "https://t.me/s1ddhants1"
    ),
    Contributor(
        avatarRes = R.drawable.ic_avatar_juby210,
        name = "Juby210",
        role = "Original Author",
        githubUrl = "https://github.com/Juby210"
    )
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Spacer(Modifier.height(AppSpacing.xxs))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = stringResource(R.string.cd_app_icon),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                )
            }
        }

        SettingsSectionCard(title = stringResource(R.string.about_authors_maintainers)) {
            CONTRIBUTORS.forEachIndexed { index, contributor ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = AppSpacing.xs)
                    )
                }
                ContributorItemRow(contributor = contributor, onOpenUrl = { uriHandler.openUri(it) })
            }
        }

        Spacer(Modifier.height(AppSpacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://github.com/s1ddhants1/SwiftBackupPrem") },
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github_logo),
                    contentDescription = stringResource(R.string.btn_view_source_github),
                    modifier = Modifier.size(24.dp)
                )
            }

            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://t.me/SwiftBackupPrem") },
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram_logo),
                    contentDescription = stringResource(R.string.btn_telegram_support),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
    }
}

@Composable
fun AboutSettingsPage() = AboutScreen()

@Composable
private fun ContributorItemRow(
    contributor: Contributor,
    onOpenUrl: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            modifier = Modifier.weight(1f)
        ) {
            Image(
                painter = painterResource(id = contributor.avatarRes),
                contentDescription = contributor.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )

            Column {
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contributor.role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            contributor.githubUrl?.let { url ->
                IconButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github_logo),
                        contentDescription = "GitHub",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            contributor.telegramUrl?.let { url ->
                IconButton(
                    onClick = { onOpenUrl(url) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_telegram_logo),
                        contentDescription = "Telegram",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
