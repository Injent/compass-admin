package ru.injent.service.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import ru.injent.service.config.Access
import ru.injent.service.config.AppConfig
import kotlin.io.encoding.Base64

class AuthService(
    appConfig: AppConfig,
) {
    private val users = appConfig.access.associateBy { access -> access.login }

    fun authenticate(call: ApplicationCall): AuthUser? {
        val token = call.basicToken() ?: return null
        val decoded = runCatching { Base64.decode(token).decodeToString() }.getOrNull() ?: return null
        val separatorIndex = decoded.indexOf(':')
        if (separatorIndex < 1) return null

        val login = decoded.substring(0, separatorIndex)
        val password = decoded.substring(separatorIndex + 1)
        val access = users[login] ?: return null
        if (access.password != password) return null

        return AuthUser(
            login = access.login,
            role = access.role,
            basicToken = token,
        )
    }

    fun challengeHeader(): Pair<String, String> =
        HttpHeaders.WWWAuthenticate to """Basic realm="Compass Admin", charset="UTF-8""""

    private fun ApplicationCall.basicToken(): String? {
        val header = request.header(HttpHeaders.Authorization)
        if (header != null && header.startsWith(BASIC_PREFIX, ignoreCase = true)) {
            return header.substring(BASIC_PREFIX.length).trim().takeIf(String::isNotBlank)
        }

        return request.cookies[AUTH_COOKIE_NAME]?.takeIf(String::isNotBlank)
    }
}

data class AuthUser(
    val login: String,
    val role: Access.Role,
    val basicToken: String,
)

const val AUTH_COOKIE_NAME = "compassadmin_auth"

private const val BASIC_PREFIX = "Basic "
