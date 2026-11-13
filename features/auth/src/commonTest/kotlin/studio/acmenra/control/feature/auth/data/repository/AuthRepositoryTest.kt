package studio.acmenra.control.feature.auth.data.repository

import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.feature.auth.data.datasource.AuthRemoteDataSource
import studio.acmenra.control.feature.auth.data.model.LoginRequestDto
import studio.acmenra.control.feature.auth.data.model.LoginResponseDto
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeAuthRemoteDataSource(
    var result: NetworkResult<LoginResponseDto>,
) : AuthRemoteDataSource {
    override suspend fun login(request: LoginRequestDto): NetworkResult<LoginResponseDto> = result
}

class AuthRepositoryTest {
    @Test
    fun testSuccessfulLoginReturnsTokens() =
        runTest {
            val fakeDataSource =
                FakeAuthRemoteDataSource(
                    NetworkResult.Success(
                        LoginResponseDto(
                            accessToken = "access_123",
                            refreshToken = "refresh_456",
                        ),
                    ),
                )
            val repository = AuthRepositoryImpl(fakeDataSource)

            val result = repository.login(LoginCredentials("user@example.com", "pass"))
            assertTrue(result is NetworkResult.Success)
            assertEquals("access_123", result.data.accessToken)
            assertEquals("refresh_456", result.data.refreshToken)
        }

    @Test
    fun testEmptyTokensInResponseProducesSerializationError() =
        runTest {
            val fakeDataSource =
                FakeAuthRemoteDataSource(
                    NetworkResult.Success(
                        LoginResponseDto(
                            accessToken = "",
                            refreshToken = "refresh_456",
                        ),
                    ),
                )
            val repository = AuthRepositoryImpl(fakeDataSource)

            val result = repository.login(LoginCredentials("user@example.com", "pass"))
            assertTrue(result is NetworkResult.Failure)
            assertTrue(result.error is NetworkError.Serialization)
        }
}
