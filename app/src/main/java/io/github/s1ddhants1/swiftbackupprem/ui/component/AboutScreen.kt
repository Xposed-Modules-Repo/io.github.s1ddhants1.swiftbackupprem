package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.s1ddhants1.swiftbackupprem.BuildConfig
import io.github.s1ddhants1.swiftbackupprem.R

private data class Contributor(
    @DrawableRes val avatarRes: Int,
    val name: String,
    @StringRes val roleRes: Int,
    val githubUrl: String,
    @StringRes val cdRes: Int
)

private data class TechItem(
    val icon: ImageVector,
    @StringRes val labelRes: Int
)

private val CONTRIBUTORS = listOf(
    Contributor(R.drawable.ic_avatar_juby210, "Juby210", R.string.about_original_author_role, "https://github.com/Juby210", R.string.cd_author_avatar),
    Contributor(R.drawable.ic_avatar_s1ddhants1, "s1ddhants1", R.string.about_maintainer_role, "https://github.com/s1ddhants1", R.string.cd_maintainer_avatar)
)

private val TECHNOLOGIES = listOf(
    TechItem(Icons.Default.Memory, R.string.about_lib_dexkit),
    TechItem(Icons.Default.Extension, R.string.about_lib_libxposed),
    TechItem(Icons.Default.Android, R.string.about_lib_compose),
    TechItem(Icons.Default.DataObject, R.string.about_lib_serialization)
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = stringResource(R.string.cd_app_icon),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = stringResource(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.about_app_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
            }
        }

        AboutSectionCard(title = stringResource(R.string.about_authors_maintainers)) {
            CONTRIBUTORS.forEachIndexed { index, contributor ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                ContributorRow(contributor) { uriHandler.openUri(it) }
            }
        }

        AboutSectionCard(title = stringResource(R.string.about_libraries_technologies)) {
            TECHNOLOGIES.forEach { tech ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(tech.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(stringResource(tech.labelRes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = { uriHandler.openUri("https://github.com/s1ddhants1/SwiftBackupPrem") },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(painter = painterResource(id = R.drawable.ic_github), contentDescription = stringResource(R.string.cd_github_icon), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.btn_view_source_github), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AboutSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ContributorRow(contributor: Contributor, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(id = contributor.avatarRes),
                contentDescription = stringResource(contributor.cdRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(width = 1.5.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), shape = CircleShape)
            )
            Column {
                Text(text = contributor.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(text = stringResource(contributor.roleRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = { onOpenUrl(contributor.githubUrl) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(painter = painterResource(id = R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(text = stringResource(R.string.btn_github), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(12.dp))
        }
    }
}
