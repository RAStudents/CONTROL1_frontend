package studio.acmenra.control.feature.auth.data.datasource

import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.data.model.LoginRequestDto
import studio.acmenra.control.feature.auth.data.model.LoginResponseDto

interface AuthRemoteDataSource {
    suspend fun login(request: LoginRequestDto): NetworkResult<LoginResponseDto>
}
