package studio.acmenra.control.feature.events.data.datasource

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal data class ExportPayload(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportPayload) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * contentType.hashCode() + bytes.contentHashCode()
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal object ExportPayloadRegistry {
    private val store = kotlin.concurrent.atomics.AtomicReference<Map<String, ExportPayload>>(emptyMap())

    fun register(
        handle: String,
        payload: ExportPayload,
    ) {
        while (true) {
            val current = store.load()
            val next = current + (handle to payload)
            if (store.compareAndSet(current, next)) break
        }
    }

    fun consume(handle: String): ExportPayload? {
        while (true) {
            val current = store.load()
            val payload = current[handle] ?: return null
            val next = current - handle
            if (store.compareAndSet(current, next)) return payload
        }
    }

    fun remove(handle: String) {
        while (true) {
            val current = store.load()
            if (!current.containsKey(handle)) break
            val next = current - handle
            if (store.compareAndSet(current, next)) break
        }
    }

    fun clear() {
        store.store(emptyMap())
    }

    fun count(): Int = store.load().size
}
