package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AccessErrorResponseDto(
    @SerialName("error")
    val error: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("company_id")
    val companyId: String? = null,
    @SerialName("restriction")
    val restriction: AccessRestrictionDto? = null,
) {
    val resolvedCode: String?
        get() = error ?: errorCode

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun parse(errorBody: String?): AccessErrorResponseDto? {
            if (errorBody.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<AccessErrorResponseDto>(errorBody) }.getOrNull()
        }
    }
}
