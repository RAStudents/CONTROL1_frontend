package studio.acmenra.control.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AmberPalette {
    val Primary = Color(0xFF8B5000)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFFFDCC2)
    val OnPrimaryContainer = Color(0xFF2C1600)

    val Secondary = Color(0xFF725A44)
    val SecondaryContainer = Color(0xFFF6DFC8)
    val OnSecondaryContainer = Color(0xFF271905)

    val TertiaryContainer = Color(0xFFD5EDC0)
    val OnTertiaryContainer = Color(0xFF0E2004)

    val Surface = Color(0xFFFFF8F5)
    val SurfaceContainerLow = Color(0xFFFCF1EA)
    val SurfaceContainer = Color(0xFFF7ECE4)
    val SurfaceContainerHigh = Color(0xFFF3E6DE)
    val SurfaceContainerHighest = Color(0xFFEDE0D8)

    val OnSurface = Color(0xFF211A14)
    val OnSurfaceVariant = Color(0xFF51443B)
    val Outline = Color(0xFF83746A)
    val OutlineVariant = Color(0xFFD6C3B6)

    val InverseSurface = Color(0xFF362F28)
    val InverseOnSurface = Color(0xFFFBEEE5)
    val InversePrimary = Color(0xFFFFB77C)

    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFF9DEDC)
    val OnErrorContainer = Color(0xFF410E0B)
}

private val AmberLightColorScheme =
    lightColorScheme(
        primary = AmberPalette.Primary,
        onPrimary = AmberPalette.OnPrimary,
        primaryContainer = AmberPalette.PrimaryContainer,
        onPrimaryContainer = AmberPalette.OnPrimaryContainer,
        secondary = AmberPalette.Secondary,
        secondaryContainer = AmberPalette.SecondaryContainer,
        onSecondaryContainer = AmberPalette.OnSecondaryContainer,
        tertiaryContainer = AmberPalette.TertiaryContainer,
        onTertiaryContainer = AmberPalette.OnTertiaryContainer,
        surface = AmberPalette.Surface,
        background = AmberPalette.Surface,
        surfaceContainerLow = AmberPalette.SurfaceContainerLow,
        surfaceContainer = AmberPalette.SurfaceContainer,
        surfaceContainerHigh = AmberPalette.SurfaceContainerHigh,
        surfaceContainerHighest = AmberPalette.SurfaceContainerHighest,
        onSurface = AmberPalette.OnSurface,
        onBackground = AmberPalette.OnSurface,
        onSurfaceVariant = AmberPalette.OnSurfaceVariant,
        outline = AmberPalette.Outline,
        outlineVariant = AmberPalette.OutlineVariant,
        inverseSurface = AmberPalette.InverseSurface,
        inverseOnSurface = AmberPalette.InverseOnSurface,
        inversePrimary = AmberPalette.InversePrimary,
        error = AmberPalette.Error,
        onError = AmberPalette.OnError,
        errorContainer = AmberPalette.ErrorContainer,
        onErrorContainer = AmberPalette.OnErrorContainer,
    )

object AmberDarkPalette {
    val Primary = Color(0xFFFDB976)
    val OnPrimary = Color(0xFF472A07)
    val PrimaryContainer = Color(0xFF683C05)
    val OnPrimaryContainer = Color(0xFFFDDDC1)

    val Secondary = Color(0xFFDEC1A8)
    val SecondaryContainer = Color(0xFF59422E)
    val OnSecondaryContainer = Color(0xFFFBDDC4)

    val TertiaryContainer = Color(0xFF384D25)
    val OnTertiaryContainer = Color(0xFFD2EABA)

    val Surface = Color(0xFF17130F)
    val SurfaceContainerLow = Color(0xFF1F1B18)
    val SurfaceContainer = Color(0xFF231F1C)
    val SurfaceContainerHigh = Color(0xFF2D2926)
    val SurfaceContainerHighest = Color(0xFF383431)

    val OnSurface = Color(0xFFE7E1DD)
    val OnSurfaceVariant = Color(0xFFD1C4BA)
    val Outline = Color(0xFF9A8F85)
    val OutlineVariant = Color(0xFF4F453D)

    val InverseSurface = Color(0xFFE7E1DD)
    val InverseOnSurface = Color(0xFF34302C)
    val InversePrimary = Color(0xFF8B5000)

    val Error = Color(0xFFF2B8B5)
    val OnError = Color(0xFF601410)
    val ErrorContainer = Color(0xFF8C1D18)
    val OnErrorContainer = Color(0xFFF9DEDC)
}

private val AmberDarkColorScheme =
    darkColorScheme(
        primary = AmberDarkPalette.Primary,
        onPrimary = AmberDarkPalette.OnPrimary,
        primaryContainer = AmberDarkPalette.PrimaryContainer,
        onPrimaryContainer = AmberDarkPalette.OnPrimaryContainer,
        secondary = AmberDarkPalette.Secondary,
        secondaryContainer = AmberDarkPalette.SecondaryContainer,
        onSecondaryContainer = AmberDarkPalette.OnSecondaryContainer,
        tertiaryContainer = AmberDarkPalette.TertiaryContainer,
        onTertiaryContainer = AmberDarkPalette.OnTertiaryContainer,
        surface = AmberDarkPalette.Surface,
        background = AmberDarkPalette.Surface,
        surfaceContainerLow = AmberDarkPalette.SurfaceContainerLow,
        surfaceContainer = AmberDarkPalette.SurfaceContainer,
        surfaceContainerHigh = AmberDarkPalette.SurfaceContainerHigh,
        surfaceContainerHighest = AmberDarkPalette.SurfaceContainerHighest,
        onSurface = AmberDarkPalette.OnSurface,
        onBackground = AmberDarkPalette.OnSurface,
        onSurfaceVariant = AmberDarkPalette.OnSurfaceVariant,
        outline = AmberDarkPalette.Outline,
        outlineVariant = AmberDarkPalette.OutlineVariant,
        inverseSurface = AmberDarkPalette.InverseSurface,
        inverseOnSurface = AmberDarkPalette.InverseOnSurface,
        inversePrimary = AmberDarkPalette.InversePrimary,
        error = AmberDarkPalette.Error,
        onError = AmberDarkPalette.OnError,
        errorContainer = AmberDarkPalette.ErrorContainer,
        onErrorContainer = AmberDarkPalette.OnErrorContainer,
    )

object AmberCorners {
    val None = 0.dp // flat or attached edge
    val ExtraSmall = 4.dp // dropdown menus
    val Small = 8.dp // inner corners of lists and connected groups
    val Medium = 16.dp // text fields
    val Large = 20.dp // cards
    val ExtraLarge = 28.dp // dialogs, large blocks, outer corners of lists
}

private val AmberShapes =
    Shapes(
        extraSmall = RoundedCornerShape(AmberCorners.ExtraSmall),
        small = RoundedCornerShape(AmberCorners.Small),
        medium = RoundedCornerShape(AmberCorners.Medium),
        large = RoundedCornerShape(AmberCorners.Large),
        extraLarge = RoundedCornerShape(AmberCorners.ExtraLarge),
    )

object AmberDimens {
    val ScreenMargin = 16.dp
    val GapSmall = 8.dp
    val GapMedium = 16.dp
    val ListGap = 3.dp
    val ListItemHeight = 72.dp
    val ListIconCircle = 40.dp
    val ListIcon = 24.dp
    val TextFieldHeight = 56.dp
    val EventsFilterFieldHeight = 64.dp
    val ButtonHeight = 56.dp
    val TopBarHeight = 64.dp
    val NavBarHeight = 80.dp
    val NavigationBarHeight = NavBarHeight
    val RailCollapsed = 96.dp
    val RailExpanded = 220.dp
    val RailTopPadding = 24.dp
    val DialogWidth = 312.dp
    val DialogMaxWidth = 440.dp
    val CardPadding = 20.dp
    val SliderTrack = 16.dp
    val SliderHandleWidth = 4.dp
    val SliderHandleHeight = 44.dp

    // Events screen workspace and detail layout dimensions
    val EventsMediumWorkspaceMin = 760.dp
    val EventsListWide = 360.dp
    val EventsListMedium = 320.dp
    val EventsDetailMin = 424.dp
    val EventsSummaryExportHeight = 144.dp
    val EventsSummaryPadding = 12.dp
    val EventsDevicesCardPadding = 8.dp
    val EventsDevicesBoxMinWidth = 120.dp
    val EventsDevicesBoxCompactWidth = 90.dp
    val EventsCompactButtonHeight = 40.dp
    val EventsCompactFilterBarHeight = 44.dp
    val EventsFieldActionSlot = 48.dp
    val EventsDropdownMaxHeight = 280.dp
    val EventsDropdownIndent = 16.dp
    val EventsSmallIcon = 16.dp
    val EventsGapTiny = 4.dp
    val EventsSkeletonWidthWide = 100.dp
    val EventsSkeletonWidthNarrow = 80.dp
    val EventsSkeletonHeight = 12.dp

    // Width at which the layout switches from phone to tablet/desktop
    val TabletBreakpoint = 840.dp

    val LoginDesktopCardMaxWidth = 616.dp
    val LoginDesktopCardMaxHeight = 800.dp
    val LoginFormDesktopWidth = 384.dp
    val LoginFormMobileWidth = 365.dp
    val ButtonLoadingIndicatorSize = 24.dp

    val NoticeMaxWidth = 560.dp
    val NoticeElevation = 6.dp
    val NoticeTimerHeight = 3.dp
    val NoticeVerticalPadding = 12.dp
    val NoticeContentGap = 12.dp

    val RestrictedIconSize = 64.dp
    val RestrictedCardMaxWidth = 480.dp
    val SupportBannerMinHeight = 48.dp
    val SupportBannerCountdownMinWidth = 60.dp
    val CompanySwitcherWidth = 240.dp
    val CompanySwitcherHeight = 40.dp
    val OutlinedBorderWidth = 1.dp
    val StatusBadgePaddingHorizontal = 8.dp
    val StatusBadgePaddingVertical = 2.dp
}

object AmberBreakpoints {
    val EventsCompact = 840.dp
    val EventsMedium = EventsCompact
    val EventsWide = 1120.dp
}

// FontFamily.Default is Roboto on Android only. On web bundle Roboto in composeResources/font
// (also needed for correct Cyrillic glyphs) and use it here.
private val Roboto = FontFamily.Default

private val AmberTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 57.sp,
                lineHeight = 64.sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 45.sp,
                lineHeight = 52.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = Roboto,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) AmberDarkColorScheme else AmberLightColorScheme,
        shapes = AmberShapes,
        typography = AmberTypography,
        // Smooth transitions with no bounce
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}
