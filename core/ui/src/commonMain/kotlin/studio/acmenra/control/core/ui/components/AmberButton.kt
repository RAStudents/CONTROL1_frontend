package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import studio.acmenra.control.core.ui.theme.AmberDimens

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(AmberDimens.ButtonHeight),
        enabled = enabled && !isLoading,
        shape = MaterialTheme.shapes.medium,
    ) {
        if (isLoading) {
            LoadingIndicator(
                modifier = Modifier.size(AmberDimens.ButtonLoadingIndicatorSize),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
