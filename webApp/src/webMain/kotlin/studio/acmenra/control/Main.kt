package studio.acmenra.control

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import kotlinx.browser.document
import studio.acmenra.control.di.initKoin

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    initKoin()

    val body = document.body ?: return

    ComposeViewport(body) {
        App(
            onNavHostReady = { navController ->
                navController.bindToBrowserNavigation()
            },
        )
    }
}
