package studio.acmenra.control.core.access.domain

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext

enum class OriginVerdict {
    VALID,
    ROGUE,
    NEEDS_GLOBAL,
}

object TenantAccessValidator {
    /**
     * Выносит вердикт о легитимности полученных происхождений [activeOrigins].
     *
     * - [OriginVerdict.VALID]: происхождение легитимно (не VENDOR_GLOBAL, либо подтверждено
     *   членством, грантом или наличием [GlobalPermission.COMPANIES_MANAGE]).
     * - [OriginVerdict.ROGUE]: заявлен VENDOR_GLOBAL без легитимных источников и без прав вендора.
     * - [OriginVerdict.NEEDS_GLOBAL]: требуется глобальный контекст для принятия решения ([userContext] == null).
     */
    fun verdict(
        activeOrigins: Set<AccessOrigin>,
        userContext: GlobalUserContext?,
    ): OriginVerdict {
        if (!activeOrigins.contains(AccessOrigin.VENDOR_GLOBAL)) {
            return OriginVerdict.VALID
        }
        val hasLegitimateOrigins =
            activeOrigins.any {
                it == AccessOrigin.DIRECT_MEMBERSHIP || it == AccessOrigin.SUPPORT_GRANT
            }
        if (hasLegitimateOrigins) {
            return OriginVerdict.VALID
        }
        if (userContext == null) {
            return OriginVerdict.NEEDS_GLOBAL
        }
        val hasCompaniesManage =
            userContext.globalPermissions.contains(GlobalPermission.COMPANIES_MANAGE)
        return if (hasCompaniesManage) OriginVerdict.VALID else OriginVerdict.ROGUE
    }

    /**
     * Совместимый метод обратной совместимости для проверок Boolean?.
     */
    fun isRogueVendorOrigin(
        activeOrigins: Set<AccessOrigin>,
        userContext: GlobalUserContext?,
    ): Boolean? =
        when (verdict(activeOrigins, userContext)) {
            OriginVerdict.VALID -> false
            OriginVerdict.ROGUE -> true
            OriginVerdict.NEEDS_GLOBAL -> null
        }
}
