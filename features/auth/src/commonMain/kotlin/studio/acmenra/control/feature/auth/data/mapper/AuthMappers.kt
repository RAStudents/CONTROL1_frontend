package studio.acmenra.control.feature.auth.data.mapper

import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.feature.auth.data.model.LoginRequestDto
import studio.acmenra.control.feature.auth.data.model.LoginResponseDto
import studio.acmenra.control.feature.auth.domain.model.AuthTokens
import studio.acmenra.control.feature.auth.domain.model.LoginCredentials

fun LoginCredentials.toDto(): LoginRequestDto =
    LoginRequestDto(
        email = email,
        password = password,
    )

fun LoginResponseDto.toDomain(): AuthTokens =
    AuthTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
    )

fun AuthTokens.toTokenPair(): TokenPair =
    TokenPair(
        accessToken = accessToken,
        refreshToken = refreshToken,
    )
