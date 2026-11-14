package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_key
import studio.acmenra.control.core.ui.generated.resources.ic_mail
import studio.acmenra.control.core.ui.generated.resources.ic_visibility
import studio.acmenra.control.core.ui.generated.resources.ic_visibility_off
import studio.acmenra.control.core.ui.generated.resources.password_hide
import studio.acmenra.control.core.ui.generated.resources.password_show

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AmberEmailField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    onFocusLost: () -> Unit = {},
) {
    var hadFocus by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        onFocusLost()
                    }
                }.semantics {
                    contentType = ContentType.EmailAddress + ContentType.Username
                },
        enabled = enabled,
        label = { Text(text = label) },
        leadingIcon = {
            Icon(
                painter = painterResource(Res.drawable.ic_mail),
                contentDescription = null,
            )
        },
        isError = isError,
        supportingText =
            if (!errorText.isNullOrBlank()) {
                { Text(text = errorText) }
            } else {
                null
            },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = imeAction,
            ),
        keyboardActions =
            KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() },
            ),
        shape = MaterialTheme.shapes.medium,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AmberPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier.semantics {
                contentType = ContentType.Password
            },
        enabled = enabled,
        label = { Text(text = label) },
        leadingIcon = {
            Icon(
                painter = painterResource(Res.drawable.ic_key),
                contentDescription = null,
            )
        },
        trailingIcon = {
            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                Icon(
                    painter =
                        painterResource(
                            if (isPasswordVisible) Res.drawable.ic_visibility else Res.drawable.ic_visibility_off,
                        ),
                    contentDescription =
                        stringResource(
                            if (isPasswordVisible) Res.string.password_hide else Res.string.password_show,
                        ),
                )
            }
        },
        isError = isError,
        supportingText =
            if (!errorText.isNullOrBlank()) {
                { Text(text = errorText) }
            } else {
                null
            },
        visualTransformation =
            if (isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = { onImeAction() },
            ),
        shape = MaterialTheme.shapes.medium,
    )
}
