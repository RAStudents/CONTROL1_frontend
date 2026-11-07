package studio.acmenra.control.core.network.auth

import studio.acmenra.control.core.network.model.TokenPair

interface TokenStorage {
    fun getTokens(): TokenPair?

    fun saveTokens(tokens: TokenPair)

    fun clear()

    fun hasTokens(): Boolean = getTokens()?.isValid() == true
}
