package studio.acmenra.control.core.access.domain.usecase.impl

import studio.acmenra.control.core.access.domain.AccessManager
import studio.acmenra.control.core.access.domain.usecase.RefreshGlobalAccessUseCase

class RefreshGlobalAccessUseCaseImpl(
    private val accessManager: AccessManager,
) : RefreshGlobalAccessUseCase {
    override suspend fun invoke(force: Boolean) {
        accessManager.refreshGlobalContext(force = force)
    }
}
