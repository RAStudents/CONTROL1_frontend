package studio.acmenra.control.core.access.domain.usecase

interface RefreshTenantUseCase {
    suspend operator fun invoke(force: Boolean = true)
}
