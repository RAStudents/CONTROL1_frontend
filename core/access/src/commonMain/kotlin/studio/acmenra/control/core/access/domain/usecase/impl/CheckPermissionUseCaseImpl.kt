package studio.acmenra.control.core.access.domain.usecase.impl

import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.usecase.CheckPermissionUseCase

class CheckPermissionUseCaseImpl(
    private val coordinator: TenantContextCoordinator,
) : CheckPermissionUseCase {
    override fun invoke(requirement: RoutePermissionRequirement): Boolean {
        val current = coordinator.tenantState.value
        if (current !is TenantContextState.Authorized) return false
        return requirement.isSatisfiedBy(current.context)
    }
}
