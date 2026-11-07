package studio.acmenra.control.core.network.engine

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun createHttpEngineFactory(): HttpClientEngineFactory<*> = OkHttp
