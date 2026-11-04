package com.github.jpmand.openprojectideaintegraiton.api

import com.github.jpmand.openprojectideaintegraiton.api.hal.HalJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.net.Authenticator
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import com.intellij.openapi.diagnostic.Logger as IdeLogger
import io.ktor.client.plugins.logging.Logger as KtorLogger

data class OpenProjectClientConfig(
    /** Normalized base URL of the installation, e.g. "https://op.example.com" or "https://example.com/openproject". */
    val baseUrl: String,
    /** API token; sent as HTTP Basic `apikey:<token>`, which every supported OpenProject version accepts. */
    val apiToken: String?,
    val userAgent: String = "OpenProject-IntelliJ-Plugin",
    /** The IDE's SSL context (trusted certificates); `null` uses the JVM default. */
    val sslContext: SSLContext? = null,
    /** Proxy authenticator; the IDE's proxy selector is the JVM default and is picked up automatically. */
    val authenticator: Authenticator? = null,
    val connectTimeoutMillis: Long = 15_000,
    val requestTimeoutMillis: Long = 60_000,
)

object OpenProjectUrls {
    /** Accepts "op.example.com", "https://op.example.com/", ".../api/v3" and returns "https://op.example.com". */
    fun normalizeBaseUrl(url: String): String {
        var normalized = url.trim().trimEnd('/')
        if (normalized.isEmpty()) return normalized
        if (!normalized.contains("://")) normalized = "https://$normalized"
        return normalized.removeSuffix("/api/v3").trimEnd('/')
    }
}

object OpenProjectHttpClient {
    private val LOG = IdeLogger.getInstance(OpenProjectHttpClient::class.java)

    /** Creates a client on the JDK HTTP engine (or [engine], for tests). */
    fun create(config: OpenProjectClientConfig, engine: HttpClientEngine? = null): HttpClient {
        val common: HttpClientConfig<*>.() -> Unit = { installCommon(config) }
        if (engine != null) return HttpClient(engine, common)
        return HttpClient(Java) {
            engine {
                config {
                    config.sslContext?.let { sslContext(it) }
                    config.authenticator?.let { authenticator(it) }
                    connectTimeout(Duration.ofMillis(config.connectTimeoutMillis))
                }
            }
            common()
        }
    }

    private fun HttpClientConfig<*>.installCommon(config: OpenProjectClientConfig) {
        install(ContentNegotiation) {
            json(HalJson.read, HalJson.contentType)
            json(HalJson.read)
        }
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMillis
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.requestTimeoutMillis
        }
        install(HttpRequestRetry) {
            retryIf(maxRetries = 2) { request, response -> request.method == HttpMethod.Get && response.status.value >= 500 }
            retryOnExceptionIf(maxRetries = 2) { request, cause -> request.method == HttpMethod.Get && cause is IOException }
            exponentialDelay()
        }
        install(UserAgent) {
            agent = config.userAgent
        }
        install(Logging) {
            logger = object : KtorLogger {
                override fun log(message: String) = LOG.debug(message)
            }
            level = LogLevel.INFO
            sanitizeHeader { it == HttpHeaders.Authorization }
        }
        config.apiToken?.takeIf { it.isNotBlank() }?.let { token -> install(apiKeyAuth(Url(config.baseUrl), token)) }
    }

    /** Adds the API key only to requests for the OpenProject host, never to redirects or external download URLs. */
    private fun apiKeyAuth(base: Url, token: String) = createClientPlugin("OpenProjectApiKeyAuth") {
        val header = "Basic " + Base64.getEncoder().encodeToString("apikey:$token".toByteArray(Charsets.UTF_8))
        onRequest { request, _ ->
            val url = request.url.build()
            if (url.protocol == base.protocol && url.host.equals(base.host, ignoreCase = true) && url.port == base.port) {
                request.headers[HttpHeaders.Authorization] = header
            }
        }
    }
}
