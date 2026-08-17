package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R

@Composable
fun WizardNavRow(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = stringResource(R.string.btn_next)
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.btn_back), textAlign = TextAlign.Center, softWrap = true)
        }
        Button(
            onClick = onNext,
            modifier = Modifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(nextLabel, textAlign = TextAlign.Center, softWrap = true)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
