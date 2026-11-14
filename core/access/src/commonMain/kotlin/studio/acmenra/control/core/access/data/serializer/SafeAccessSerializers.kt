package studio.acmenra.control.core.access.data.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantPermission

open class FailClosedEnumSetSerializer<E : Enum<E>>(
    private val entriesByName: Map<String, E>,
    private val nameSelector: (E) -> String = { it.name },
) : KSerializer<Set<E>> {
    private val delegateSerializer = ListSerializer(String.serializer().nullable)
    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun deserialize(decoder: Decoder): Set<E> {
        val rawItems = delegateSerializer.deserialize(decoder)
        return buildSet {
            rawItems.filterNotNull().forEach { raw -> entriesByName[raw]?.let(::add) }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Set<E>,
    ) {
        delegateSerializer.serialize(encoder, value.map(nameSelector))
    }
}

object SafeGlobalPermissionSetSerializer : FailClosedEnumSetSerializer<GlobalPermission>(
    GlobalPermission.entries.associateBy { it.name },
)

object SafeTenantPermissionSetSerializer : FailClosedEnumSetSerializer<TenantPermission>(
    TenantPermission.entries.associateBy { it.name },
)

object SafeSupportPermissionSetSerializer : FailClosedEnumSetSerializer<SupportPermission>(
    SupportPermission.entries.associateBy { it.name },
)

object SafeAccessOriginSetSerializer : FailClosedEnumSetSerializer<AccessOrigin>(
    AccessOrigin.entries.filterNot { it == AccessOrigin.UNKNOWN }.associateBy { it.name },
)

open class FallbackEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val entriesByName: Map<String, E>,
    private val fallbackValue: E,
    private val nameSelector: (E) -> String = { it.name },
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E = entriesByName[decoder.decodeString()] ?: fallbackValue

    override fun serialize(
        encoder: Encoder,
        value: E,
    ) = encoder.encodeString(nameSelector(value))
}

object SafeCompanyStatusSerializer : FallbackEnumSerializer<CompanyStatus>(
    "CompanyStatus",
    CompanyStatus.entries.associateBy { it.name },
    CompanyStatus.UNKNOWN,
)

object SafeAccessStatusSerializer : FallbackEnumSerializer<AccessStatus>(
    "AccessStatus",
    AccessStatus.entries.associateBy { it.name },
    AccessStatus.UNKNOWN,
)

object SafeAccessOriginSerializer : FallbackEnumSerializer<AccessOrigin>(
    "AccessOrigin",
    AccessOrigin.entries.associateBy { it.name },
    AccessOrigin.UNKNOWN,
)

object SafeRestrictionTypeSerializer : FallbackEnumSerializer<RestrictionType>(
    "RestrictionType",
    RestrictionType.entries.associateBy { it.name },
    RestrictionType.UNKNOWN,
)

object SafeSupportModeSerializer : FallbackEnumSerializer<SupportMode>(
    "SupportMode",
    SupportMode.entries.associateBy { it.name },
    SupportMode.UNKNOWN,
)

object SafeSupportGrantStatusSerializer : FallbackEnumSerializer<SupportGrantStatus>(
    "SupportGrantStatus",
    SupportGrantStatus.entries.associateBy { it.name },
    SupportGrantStatus.UNKNOWN,
)
