package studio.acmenra.control.core.access.domain

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext

object TenantAccessValidator {
    /**
     * Проверяет, является ли полученный origin [AccessOrigin.VENDOR_GLOBAL] нелегитимным ("rogue").
     * Возвращает true, если контекст компании заявляет происхождение VENDOR_GLOBAL,
     * но у пользователя нет глобального разрешения [GlobalPermission.COMPANIES_MANAGE] и нет других
     * легитимных источников доступа (DIRECT_MEMBERSHIP или SUPPORT_GRANT).
     *
     * При [userContext] == null возвращает null (требуется повторная валидация глобального контекста,
     * отказ в доступе не выносится вслепую).
     */
    fun isRogueVendorOrigin(
        activeOrigins: Set<AccessOrigin>,
        userContext: GlobalUserContext?,
    ): Boolean? {
        if (!activeOrigins.contains(AccessOrigin.VENDOR_GLOBAL)) {
            return false
        }
        val hasLegitimateOrigins =
            activeOrigins.any {
                it == AccessOrigin.DIRECT_MEMBERSHIP || it == AccessOrigin.SUPPORT_GRANT
            }
        if (hasLegitimateOrigins) {
            return false
        }
        if (userContext == null) {
            return null
        }
        val hasCompaniesManage =
            userContext.globalPermissions.contains(GlobalPermission.COMPANIES_MANAGE)
        return !hasCompaniesManage
    }
}
