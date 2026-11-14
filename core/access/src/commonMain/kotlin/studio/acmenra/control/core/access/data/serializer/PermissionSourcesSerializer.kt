package studio.acmenra.control.core.access.data.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.PermissionKey

/** Преобразует JSON object со строковыми ключами в типизированную доменную карту. */
object PermissionSourcesSerializer : KSerializer<Map<PermissionKey, Set<AccessOrigin>>> {
    private val delegate = MapSerializer(String.serializer(), SafeAccessOriginSetSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<PermissionKey, Set<AccessOrigin>> {
        val raw = delegate.deserialize(decoder)
        return buildMap {
            raw.forEach { (name, origins) ->
                val key = PermissionKey.fromRawName(name) ?: return@forEach
                if (origins.isNotEmpty()) put(key, origins)
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Map<PermissionKey, Set<AccessOrigin>>,
    ) {
        val raw =
            value.entries.associate { (key, origins) ->
                val name =
                    when (key) {
                        is PermissionKey.Global -> key.permission.name
                        is PermissionKey.Tenant -> key.permission.name
                        is PermissionKey.Support -> key.permission.name
                    }
                name to origins
            }
        delegate.serialize(encoder, raw)
    }
}
