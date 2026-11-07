package studio.acmenra.control.core.network.engine

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

actual fun createHttpEngineFactory(): HttpClientEngineFactory<*> = Js
