package studio.acmenra.control.core.access.domain.usecase

interface EnsureTenantContextUseCase {
    suspend operator fun invoke(companyId: String)
}
