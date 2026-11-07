package studio.acmenra.control.core.network.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.clearAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthClientRegistry {
    private val mutex = Mutex()
    private val clients = mutableSetOf<HttpClient>()

    fun register(client: HttpClient) {
        clients.add(client)
    }

    fun unregister(client: HttpClient) {
        clients.remove(client)
    }

    suspend fun clearAllAuthTokens() {
        mutex.withLock {
            clients.forEach { it.clearAuthTokens() }
        }
    }
}
