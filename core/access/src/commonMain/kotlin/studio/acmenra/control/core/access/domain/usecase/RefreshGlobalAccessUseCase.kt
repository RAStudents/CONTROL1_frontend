package studio.acmenra.control.core.access.domain.usecase

interface RefreshGlobalAccessUseCase {
    suspend operator fun invoke(force: Boolean = true)
}
