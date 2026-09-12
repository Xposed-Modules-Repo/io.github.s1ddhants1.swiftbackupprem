package io.github.s1ddhants1.swiftbackupprem.ui.component

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

@Composable
fun FirebaseSetupScreen(
    prefs: PreferencesManager,
    onImportGoogleServices: (Uri) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        FirebaseCredentialsSection(
            prefs = prefs,
            onImportGoogleServices = onImportGoogleServices
        )

        Spacer(modifier = Modifier.height(64.dp))
    }
}
