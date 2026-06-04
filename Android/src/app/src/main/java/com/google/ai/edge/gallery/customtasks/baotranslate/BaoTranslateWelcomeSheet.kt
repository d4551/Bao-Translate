package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaoTranslateWelcomeSheet(
  onDismiss: () -> Unit,
  isTablet: Boolean = false,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth
  
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
  
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.focusRequester(focusRequester),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = maxWidth)
        .padding(horizontal = Dimensions.Spacing.large, vertical = Dimensions.Spacing.medium),
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_welcome_title),
        style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )

      WelcomeStep(
        icon = Icons.Default.Download,
        title = stringResource(R.string.bao_translate_welcome_step_models_title),
        body = stringResource(R.string.bao_translate_welcome_step_models_body),
      )

      WelcomeStep(
        icon = Icons.Default.BluetoothAudio,
        title = stringResource(R.string.bao_translate_welcome_step_audio_title),
        body = stringResource(R.string.bao_translate_welcome_step_audio_body),
      )

      WelcomeStep(
        icon = Icons.Default.Mic,
        title = stringResource(R.string.bao_translate_welcome_step_speak_title),
        body = stringResource(R.string.bao_translate_welcome_step_speak_body),
      )

      Spacer(modifier = Modifier.size(Dimensions.Spacing.small))

      Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.bao_translate_welcome_get_started))
      }
    }
  }
}

@Composable
private fun WelcomeStep(icon: ImageVector, title: String, body: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(Dimensions.Icon.medium),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
