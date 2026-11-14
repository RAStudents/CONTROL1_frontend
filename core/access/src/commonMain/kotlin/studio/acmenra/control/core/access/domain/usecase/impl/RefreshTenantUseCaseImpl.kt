package studio.acmenra.control.core.access.domain.usecase.impl

import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.usecase.RefreshTenantUseCase

class RefreshTenantUseCaseImpl(
    private val coordinator: TenantContextCoordinator,
) : RefreshTenantUseCase {
    override suspend fun invoke(force: Boolean) {
        coordinator.refreshCurrentTenant(force = force)
    }
}
