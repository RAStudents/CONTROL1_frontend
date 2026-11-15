package studio.acmenra.control.core.access.domain.usecase.impl

import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.core.access.domain.AccessManager
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase

class ObserveGlobalAccessUseCaseImpl(
    private val accessManager: AccessManager,
) : ObserveGlobalAccessUseCase {
    override fun invoke(): StateFlow<GlobalAccessState> = accessManager.globalState
}
