package io.github.s1ddhants1.swiftbackupprem.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.s1ddhants1.swiftbackupprem.R

@Composable
fun SettingsTextField(
    label: String,
    pref: String,
    onPrefChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Next
    )
) {
    Box(modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = pref,
            onValueChange = onPrefChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            trailingIcon = if (pref.isNotBlank()) {
                {
                    IconButton(onClick = { onPrefChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.cd_clear_input),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else null
        )
    }
}
