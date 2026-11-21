package studio.acmenra.control.feature.events.domain.repository

import kotlinx.coroutines.flow.Flow

interface EventsConnectivityProvider {
    val isOnline: Flow<Boolean>
}
