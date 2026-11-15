package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import studio.acmenra.control.core.ui.theme.AmberDimens

/**
 * Переиспользуемый TopAppBar дизайн-системы Amber.
 * На широких экранах (Desktop / Tablet) отображает справа выделенный контейнер [desktopCompanyBox]
 * (селектор компании на фоне surfaceContainerHigh).
 * На мобильных экранах действие выбора компании передаётся через [mobileCompanyAction] и отображается
 * в блоке действий справа от заголовка. При наличии [subtitle] отображает его под заголовком.
 * Учитывает [windowInsets] (по умолчанию [WindowInsets.statusBars]) для корректного edge-to-edge отображения.
 */
@Composable
fun AmberTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingAction: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    mobileCompanyAction: (@Composable RowScope.() -> Unit)? = null,
    desktopCompanyBox: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .height(AmberDimens.TopBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Основная часть бара (Заголовок + подзаголовок + иконки действий)
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = AmberDimens.ScreenMargin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            ) {
                if (leadingAction != null) {
                    leadingAction()
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                ) {
                    actions()
                    if (mobileCompanyAction != null) {
                        mobileCompanyAction()
                    }
                }
            }

            // Правый контейнер свитчера компании для Desktop / Tablet
            if (desktopCompanyBox != null) {
                Surface(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = contentColor,
                    shape = RectangleShape,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .padding(horizontal = AmberDimens.ScreenMargin),
                        contentAlignment = Alignment.Center,
                    ) {
                        desktopCompanyBox()
                    }
                }
            }
        }
    }
}
