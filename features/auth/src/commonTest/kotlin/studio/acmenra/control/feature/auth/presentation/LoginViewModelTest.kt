package studio.acmenra.control.feature.auth.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.ui.notice.NoticeText
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials
import studio.acmenra.control.feature.auth.domain.usecase.LoginUseCase
import studio.acmenra.control.feature.auth.domain.validation.isValidEmail
import studio.acmenra.control.feature.auth.generated.resources.Res
import studio.acmenra.control.feature.auth.generated.resources.login_error_bad_request
import studio.acmenra.control.feature.auth.generated.resources.login_error_internal_server
import studio.acmenra.control.feature.auth.generated.resources.login_error_not_found
import studio.acmenra.control.feature.auth.generated.resources.login_error_service_unavailable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeTokenStorage : TokenStorage {
    var storedTokens: TokenPair? = null

    override fun getTokens(): TokenPair? = storedTokens

    override fun saveTokens(tokens: TokenPair) {
        storedTokens = tokens
    }

    override fun clear() {
        storedTokens = null
    }
}

private class FakeLoginUseCase : LoginUseCase {
    var result: NetworkResult<AuthTokens> =
        NetworkResult.Success(AuthTokens("access_token_123", "refresh_token_456"))
    var invocationCount = 0
    var lastCredentials: LoginCredentials? = null
    var delayMs: Long = 0L
    var throwableToThrow: Throwable? = null
    var triggerTimeout = false

    override suspend fun invoke(credentials: LoginCredentials): NetworkResult<AuthTokens> {
        invocationCount++
        lastCredentials = credentials
        if (triggerTimeout) {
            withTimeout(1) {
                delay(100)
            }
        }
        if (delayMs > 0) {
            delay(delayMs)
        }
        throwableToThrow?.let { throw it }
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var loginUseCase: FakeLoginUseCase
    private lateinit var viewModel: LoginViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tokenStorage = FakeTokenStorage()
        sessionManager = SessionManager(tokenStorage)
        loginUseCase = FakeLoginUseCase()
        viewModel = LoginViewModel(loginUseCase, sessionManager)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testEmailValidationWithSpaces() {
        val emailWithSpaces = "   user@example.com   "
        assertTrue(isValidEmail(emailWithSpaces))

        viewModel.onEmailChanged(emailWithSpaces)
        assertEquals("user@example.com", viewModel.state.value.trimmedEmail)
        assertTrue(viewModel.state.value.isEmailValid)

        val invalidEmailWithSpaces = "   invalid-email   "
        assertFalse(isValidEmail(invalidEmailWithSpaces))
        viewModel.onEmailChanged(invalidEmailWithSpaces)
        assertFalse(viewModel.state.value.isEmailValid)
    }

    @Test
    fun testEmailFormatErrorNotShownWhileTypingUntilFocusLost() {
        viewModel.onEmailChanged("invalid")
        assertFalse(
            viewModel.state.value.isEmailFormatError,
            "Error should not appear while user is still typing",
        )

        viewModel.onEmailFocusLost()
        assertTrue(
            viewModel.state.value.isEmailFormatError,
            "Error should appear after focus is lost for invalid email",
        )

        // Empty field on focus lost should NOT show format error
        viewModel.onEmailChanged("")
        viewModel.onEmailFocusLost()
        assertFalse(viewModel.state.value.isEmailFormatError)
    }

    @Test
    fun testEmailFormatErrorResetsImmediatelyWhenEmailBecomesValid() {
        viewModel.onEmailChanged("user@")
        viewModel.onEmailFocusLost()
        assertTrue(viewModel.state.value.isEmailFormatError)

        viewModel.onEmailChanged("user@example.com")
        assertFalse(
            viewModel.state.value.isEmailFormatError,
            "Error should reset immediately once valid email is entered",
        )
    }

    @Test
    fun testSubmitButtonStateAndSingleExecutionGuarantee() =
        runTest(testDispatcher) {
            // Initially empty
            assertFalse(viewModel.state.value.isSubmitEnabled)

            // Email only
            viewModel.onEmailChanged("user@example.com")
            assertFalse(viewModel.state.value.isSubmitEnabled)

            // Password only
            viewModel.onEmailChanged("")
            viewModel.onPasswordChanged("securePassword123")
            assertFalse(viewModel.state.value.isSubmitEnabled)

            // Invalid email + password
            viewModel.onEmailChanged("not-an-email")
            assertFalse(viewModel.state.value.isSubmitEnabled)

            // Valid email + password
            viewModel.onEmailChanged("user@example.com")
            assertTrue(viewModel.state.value.isSubmitEnabled)

            // Single execution guarantee
            loginUseCase.delayMs = 1000L
            viewModel.onSubmit()

            assertTrue(viewModel.state.value.isLoading)
            assertFalse(
                viewModel.state.value.isSubmitEnabled,
                "Button must be disabled during loading",
            )

            // Let coroutine start and invoke loginUseCase
            runCurrent()
            assertEquals(1, loginUseCase.invocationCount)

            // Second submit while loading should be ignored
            viewModel.onSubmit()
            runCurrent()
            assertEquals(
                1,
                loginUseCase.invocationCount,
                "Duplicate submit should be ignored while loading",
            )

            advanceUntilIdle()
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun testHttp401UnauthorizedSetsInvalidCredentialsErrorAndResetsOnInput() =
        runTest(testDispatcher) {
            loginUseCase.result =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 401,
                        message = "Unauthorized",
                    ),
                )

            viewModel.onEmailChanged("user@example.com")
            viewModel.onPasswordChanged("wrongPassword")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isInvalidCredentialsError)
            assertEquals(
                0,
                viewModel.state.value.notices.size,
                "No notice banner should be shown for 401",
            )

            // Reset error on email change
            viewModel.onEmailChanged("other@example.com")
            assertFalse(viewModel.state.value.isInvalidCredentialsError)

            // Re-trigger error and verify reset on password change
            viewModel.onSubmit()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isInvalidCredentialsError)

            viewModel.onPasswordChanged("newPassword")
            assertFalse(viewModel.state.value.isInvalidCredentialsError)
        }

    @Test
    fun testHttp500ServerErrorAddsNoticeWithErrorCodeWithoutHighlightingFields() =
        runTest(testDispatcher) {
            loginUseCase.result =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 500,
                        message = "Internal Server Error",
                    ),
                )

            viewModel.onEmailChanged("user@example.com")
            viewModel.onPasswordChanged("password")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertFalse(
                viewModel.state.value.isInvalidCredentialsError,
                "Fields should not be marked as invalid credentials for server error",
            )
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(1, viewModel.state.value.notices.size)

            val notice =
                viewModel.state.value.notices
                    .first()
            val resourceMessage = notice.message as? NoticeText.Resource
            assertNotNull(resourceMessage)
            assertEquals(Res.string.login_error_internal_server, resourceMessage.resource)
            assertEquals(listOf(500), resourceMessage.args)

            // Consuming notice
            viewModel.onNoticeConsumed(notice.id)
            assertEquals(0, viewModel.state.value.notices.size)
        }

    @Test
    fun testSuccessfulLoginSavesTokensAndSetsSuccessState() =
        runTest(testDispatcher) {
            loginUseCase.result =
                NetworkResult.Success(
                    AuthTokens(
                        accessToken = "access_token_abc",
                        refreshToken = "refresh_token_xyz",
                    ),
                )

            viewModel.onEmailChanged("   user@example.com   ")
            viewModel.onPasswordChanged("myPassword")
            viewModel.onSubmit()

            advanceUntilIdle()

            // Verify email sent to backend is trimmed
            assertEquals("user@example.com", loginUseCase.lastCredentials?.email)
            assertEquals("myPassword", loginUseCase.lastCredentials?.password)

            assertTrue(viewModel.state.value.isSuccess)
            assertFalse(viewModel.state.value.isLoading)
            // Password must be cleared from memory upon success
            assertEquals("", viewModel.state.value.password)

            // Tokens saved in session manager
            val stored = tokenStorage.getTokens()
            assertNotNull(stored)
            assertEquals("access_token_abc", stored.accessToken)
            assertEquals("refresh_token_xyz", stored.refreshToken)
            assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
        }

    @Test
    fun testUnexpectedNetworkExceptionDoesNotCrashViewModelAndEmitsNotice() =
        runTest {
            val customLoginUseCase =
                FakeLoginUseCase().apply {
                    throwableToThrow = RuntimeException("Network connection failed: Failed to fetch")
                }
            val customViewModel = LoginViewModel(customLoginUseCase, sessionManager)

            customViewModel.onEmailChanged("user@example.com")
            customViewModel.onPasswordChanged("password")
            customViewModel.onSubmit()

            advanceUntilIdle()

            assertFalse(customViewModel.state.value.isLoading)
            assertFalse(customViewModel.state.value.isSuccess)
            assertEquals(1, customViewModel.state.value.notices.size)
        }

    @Test
    fun testHttp400BadRequestWithoutAuthErrorShowsSafeServerNotice() =
        runTest {
            loginUseCase.result =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 400,
                        message = "Bad Request",
                        errorBody = """{"error":"syntax_error"}""",
                    ),
                )

            viewModel.onEmailChanged("user@example.com")
            viewModel.onPasswordChanged("wrongPassword")
            viewModel.onSubmit()

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertFalse(viewModel.state.value.isInvalidCredentialsError)
            assertEquals(1, viewModel.state.value.notices.size)
            val notice =
                viewModel.state.value.notices
                    .first()
            val resourceMessage = notice.message as? NoticeText.Resource
            assertNotNull(resourceMessage)
            assertEquals(Res.string.login_error_bad_request, resourceMessage.resource)
            assertEquals(listOf(400), resourceMessage.args)
        }

    @Test
    fun testTimeoutCancellationExceptionResetsLoadingAndShowsNotice() =
        runTest {
            val customLoginUseCase =
                FakeLoginUseCase().apply {
                    triggerTimeout = true
                }
            val customViewModel = LoginViewModel(customLoginUseCase, sessionManager)

            customViewModel.onEmailChanged("user@example.com")
            customViewModel.onPasswordChanged("password")
            customViewModel.onSubmit()

            advanceUntilIdle()

            assertFalse(customViewModel.state.value.isLoading)
            assertFalse(customViewModel.state.value.isSuccess)
            assertEquals(1, customViewModel.state.value.notices.size)
        }

    @Test
    fun testHttp503ServiceUnavailableShowsCorrectNotice() =
        runTest {
            loginUseCase.result =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 503,
                        message = "Service Unavailable",
                    ),
                )

            viewModel.onEmailChanged("user@example.com")
            viewModel.onPasswordChanged("password")
            viewModel.onSubmit()

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(1, viewModel.state.value.notices.size)
            val notice =
                viewModel.state.value.notices
                    .first()
            val resourceMessage = notice.message as? NoticeText.Resource
            assertNotNull(resourceMessage)
            assertEquals(Res.string.login_error_service_unavailable, resourceMessage.resource)
            assertEquals(listOf(503), resourceMessage.args)
        }

    @Test
    fun testHttp404EndpointNotFoundShowsCorrectNotice() =
        runTest {
            loginUseCase.result =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 404,
                        message = "Not Found",
                    ),
                )

            viewModel.onEmailChanged("user@example.com")
            viewModel.onPasswordChanged("password")
            viewModel.onSubmit()

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(1, viewModel.state.value.notices.size)
            val notice =
                viewModel.state.value.notices
                    .first()
            val resourceMessage = notice.message as? NoticeText.Resource
            assertNotNull(resourceMessage)
            assertEquals(Res.string.login_error_not_found, resourceMessage.resource)
            assertEquals(listOf(404), resourceMessage.args)
        }

    @Test
    fun testLenientEmailValidationHandlesInternationalAndSubdomains() {
        assertTrue(isValidEmail("user@domain.com"))
        assertTrue(isValidEmail("u.ser+tag@sub.domain.co"))
        assertTrue(isValidEmail("иван@почта.рф"))

        assertFalse(isValidEmail("user@domain"))
        assertFalse(isValidEmail("@domain.com"))
        assertFalse(isValidEmail("user@"))
        assertFalse(isValidEmail("user..name@domain.com"))
        assertFalse(isValidEmail("user@domain..com"))
        assertFalse(isValidEmail("user name@domain.com"))
    }
}
