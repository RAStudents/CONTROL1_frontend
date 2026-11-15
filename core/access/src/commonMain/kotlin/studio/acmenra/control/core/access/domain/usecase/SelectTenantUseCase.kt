package studio.acmenra.control.core.access.domain.usecase

interface SelectTenantUseCase {
    suspend operator fun invoke(companyId: String)
}
