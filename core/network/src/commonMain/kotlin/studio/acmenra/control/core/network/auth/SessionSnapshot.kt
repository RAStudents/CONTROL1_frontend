package studio.acmenra.control.core.network.auth

import studio.acmenra.control.core.network.model.TokenPair

data class SessionSnapshot(
    val tokens: TokenPair,
    val revision: Long,
) {
    override fun toString(): String = "SessionSnapshot(revision=$revision, tokens=REDACTED)"
}
