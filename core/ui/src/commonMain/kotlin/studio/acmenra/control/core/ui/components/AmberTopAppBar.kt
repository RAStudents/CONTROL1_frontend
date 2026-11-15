package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * На широких экранах (>= TabletBreakpoint) отображает справа выделенный контейнер [desktopCompanyBox]
 * (например, для селектора компании на фоне surfaceContainerHigh).
 * На узких экранах (< TabletBreakpoint) все действия, включая иконку смены компании, рендерятся внутри [actions].
 */
@Composable
fun AmberTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingAction: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    mobileCompanyAction: (@Composable RowScope.() -> Unit)? = null,
    desktopCompanyBox: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(AmberDimens.TopBarHeight),
    ) {
        val isDesktopOrTablet = maxWidth >= AmberDimens.TabletBreakpoint

        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Основная часть бара (Заголовок + иконки действий)
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

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                    ) {
                        actions()
                        if (!isDesktopOrTablet && mobileCompanyAction != null) {
                            mobileCompanyAction()
                        }
                    }
                }

                // Правый контейнер свитчера компании только для Desktop / Tablet
                if (isDesktopOrTablet && desktopCompanyBox != null) {
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
}
