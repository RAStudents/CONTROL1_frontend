package studio.acmenra.control.core.network.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class AccessInvalidationDispatcher : AccessInvalidationListener {
    private val listeners = MutableStateFlow<List<AccessInvalidationListener>>(emptyList())

    fun addListener(listener: AccessInvalidationListener) {
        listeners.update { current ->
            if (listener in current) current else current + listener
        }
    }

    fun removeListener(listener: AccessInvalidationListener) {
        listeners.update { current ->
            current - listener
        }
    }

    override suspend fun onAccessInvalidated(
        errorCode: String?,
        companyId: String?,
    ) {
        listeners.value.forEach { it.onAccessInvalidated(errorCode, companyId) }
    }
}
