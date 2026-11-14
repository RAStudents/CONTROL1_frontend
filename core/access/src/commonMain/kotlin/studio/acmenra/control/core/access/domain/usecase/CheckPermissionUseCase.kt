package studio.acmenra.control.core.access.domain.usecase

import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement

interface CheckPermissionUseCase {
    operator fun invoke(requirement: RoutePermissionRequirement): Boolean
}
