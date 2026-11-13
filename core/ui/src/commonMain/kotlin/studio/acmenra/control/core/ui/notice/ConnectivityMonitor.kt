package studio.acmenra.control.core.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.notice_offline_title
import studio.acmenra.control.core.ui.generated.resources.notice_online_restored

// Implementations live in androidMain and wasmJsMain
interface ConnectivityMonitor {
    val isOnline: Flow<Boolean>
}

private const val OFFLINE_KEY = "offline"

// Shows a persistent "no internet" banner while offline and a short "restored" one afterwards.
// Call once in App.kt next to NoticeHost.
@Composable
fun ConnectivityNotices(
    monitor: ConnectivityMonitor = koinInject(),
    controller: NoticeController = koinInject(),
) {
    LaunchedEffect(monitor) {
        var wasOffline = false
        monitor.isOnline.collect { online ->
            if (!online) {
                wasOffline = true
                controller.show(
                    Notice.offline(
                        title = Res.string.notice_offline_title,
                        key = OFFLINE_KEY,
                    ),
                )
            } else {
                controller.dismissKey(OFFLINE_KEY)
                if (wasOffline) {
                    wasOffline = false
                    controller.show(Notice.success(Res.string.notice_online_restored))
                }
            }
        }
    }
}
