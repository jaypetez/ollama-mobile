package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme

/**
 * The app's text field.
 *
 * [errorText] is a string rather than a boolean plus a separate helper slot,
 * because a field that turns red without saying why is the single most common
 * form validation bug and it is invisible to a screen reader — Material's
 * `isError` alone changes only the colour.
 */
@Composable
fun OllamaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(text = label) },
        placeholder = placeholder?.let { hint -> { Text(text = hint) } },
        supportingText = (errorText ?: supportingText)?.let { help -> { Text(text = help) } },
        isError = errorText != null,
        singleLine = singleLine,
        textStyle = if (monospace) MonospaceTextStyle else TextStyle.Default,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailing,
    )
}

/**
 * A text field for a credential.
 *
 * The value is masked by default and the reveal is a deliberate, momentary act:
 * nothing here is persisted, echoed to a log, or put in a saved-instance
 * bundle. See `ServerRef`'s KDoc for why the token never reaches a model type.
 */
@Composable
fun OllamaSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(text = label) },
        supportingText = (errorText ?: supportingText)?.let { help -> { Text(text = help) } },
        isError = errorText != null,
        singleLine = true,
        textStyle = MonospaceTextStyle,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.action_hide_secret else R.string.action_reveal_secret,
                    ),
                )
            }
        },
    )
}

@Preview
@Composable
private fun OllamaTextFieldPreview() {
    OllamaPreviewTheme {
        OllamaTextField(
            value = "http://192.168.1.40:11434",
            onValueChange = {},
            label = stringResource(R.string.server_field_base_url),
            monospace = true,
        )
    }
}
