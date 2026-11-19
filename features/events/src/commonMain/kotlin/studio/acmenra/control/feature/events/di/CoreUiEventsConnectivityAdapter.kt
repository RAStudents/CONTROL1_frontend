package studio.acmenra.control.feature.events.di

import kotlinx.coroutines.flow.Flow
import studio.acmenra.control.core.ui.notice.ConnectivityMonitor
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider

class CoreUiEventsConnectivityAdapter(
    private val monitor: ConnectivityMonitor,
) : EventsConnectivityProvider {
    override val isOnline: Flow<Boolean> get() = monitor.isOnline
}
