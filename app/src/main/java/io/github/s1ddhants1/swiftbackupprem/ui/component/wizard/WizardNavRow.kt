package io.github.s1ddhants1.swiftbackupprem.ui.component.wizard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.s1ddhants1.swiftbackupprem.R

@Composable
fun WizardActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    isIconAtEnd: Boolean = false,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight? = null,
    iconSize: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
) {
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (icon != null && !isIconAtEnd) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = fontSize, fontWeight = fontWeight, textAlign = TextAlign.Center, softWrap = true)
        if (icon != null && isIconAtEnd) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }

    if (isPrimary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 40.dp),
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            content = buttonContent
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 40.dp),
            contentPadding = contentPadding,
            content = buttonContent
        )
    }
}

@Composable
fun WizardNavRow(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = stringResource(R.string.btn_next)
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WizardActionButton(
            text = stringResource(R.string.btn_back),
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        )
        WizardActionButton(
            text = nextLabel,
            onClick = onNext,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            isPrimary = true,
            isIconAtEnd = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
