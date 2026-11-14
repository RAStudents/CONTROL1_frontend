package studio.acmenra.control.core.access.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GlobalPermission {
    @SerialName("COMPANIES_MONITOR")
    COMPANIES_MONITOR,

    @SerialName("COMPANIES_MANAGE")
    COMPANIES_MANAGE,

    @SerialName("GLOBAL_USERS_MANAGE")
    GLOBAL_USERS_MANAGE,
}

@Serializable
enum class TenantPermission {
    @SerialName("EVENTS_READ")
    EVENTS_READ,

    @SerialName("DEVICES_READ")
    DEVICES_READ,

    @SerialName("DEVICES_CREATE")
    DEVICES_CREATE,

    @SerialName("DEVICES_UPDATE")
    DEVICES_UPDATE,

    @SerialName("DEVICES_DELETE")
    DEVICES_DELETE,

    @SerialName("REPORTS_READ")
    REPORTS_READ,

    @SerialName("REPORTS_EXPORT")
    REPORTS_EXPORT,

    @SerialName("DICTIONARIES_READ")
    DICTIONARIES_READ,

    @SerialName("DICTIONARIES_MANAGE")
    DICTIONARIES_MANAGE,

    @SerialName("AUDIT_READ")
    AUDIT_READ,

    @SerialName("USERS_READ")
    USERS_READ,

    @SerialName("USERS_MANAGE")
    USERS_MANAGE,
}

@Serializable
enum class SupportPermission {
    @SerialName("DEVICE_DIAGNOSTICS")
    DEVICE_DIAGNOSTICS,

    @SerialName("DEVICE_RESTART")
    DEVICE_RESTART,

    @SerialName("TOKEN_ROTATE")
    TOKEN_ROTATE,
}

/** Строковые имена permissions должны быть уникальны между доменами. */
sealed interface PermissionKey {
    data class Global(
        val permission: GlobalPermission,
    ) : PermissionKey

    data class Tenant(
        val permission: TenantPermission,
    ) : PermissionKey

    data class Support(
        val permission: SupportPermission,
    ) : PermissionKey

    companion object {
        private val byName: Map<String, PermissionKey> =
            buildMap {
                GlobalPermission.entries.forEach { put(it.name, Global(it)) }
                TenantPermission.entries.forEach { put(it.name, Tenant(it)) }
                SupportPermission.entries.forEach { put(it.name, Support(it)) }
            }

        fun fromRawName(rawName: String): PermissionKey? = byName[rawName]
    }
}
