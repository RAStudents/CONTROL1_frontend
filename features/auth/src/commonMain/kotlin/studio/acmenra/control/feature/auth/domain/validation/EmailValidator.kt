package studio.acmenra.control.feature.auth.domain.validation

/**
 * Lenient client-side email format validation.
 * Verifies basic syntax: non-empty local-part and domain separated by a single '@',
 * at least one dot in domain, no consecutive dots or surrounding dots, and no whitespace.
 * The backend server remains the ultimate authority on address validity.
 */
fun isValidEmail(email: String): Boolean {
    val trimmed = email.trim()
    if (trimmed.length > 254) return false
    val atIndex = trimmed.indexOf('@')
    if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@') || atIndex == trimmed.length - 1) {
        return false
    }

    val localPart = trimmed.substring(0, atIndex)
    val domainPart = trimmed.substring(atIndex + 1)

    if (localPart.startsWith('.') || localPart.endsWith('.') || localPart.contains("..")) {
        return false
    }
    if (domainPart.startsWith('.') || domainPart.endsWith('.') || domainPart.contains("..")) {
        return false
    }
    if (!domainPart.contains('.')) {
        return false
    }
    if (trimmed.any { it.isWhitespace() }) {
        return false
    }

    return true
}
