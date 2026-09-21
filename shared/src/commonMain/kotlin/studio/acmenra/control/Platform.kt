package studio.acmenra.control

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform