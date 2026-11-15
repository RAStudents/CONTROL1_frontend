package studio.acmenra.control.core.access.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.core.access.domain.model.GlobalAccessState

interface ObserveGlobalAccessUseCase {
    operator fun invoke(): StateFlow<GlobalAccessState>
}
