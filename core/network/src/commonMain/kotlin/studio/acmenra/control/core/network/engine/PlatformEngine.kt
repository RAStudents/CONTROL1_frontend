package studio.acmenra.control.core.network.engine

import io.ktor.client.engine.HttpClientEngineFactory

expect fun createHttpEngineFactory(): HttpClientEngineFactory<*>
