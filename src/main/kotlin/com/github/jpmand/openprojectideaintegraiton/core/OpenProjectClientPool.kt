package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApi
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectClientConfig
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectHttpClient
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectUrls
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.ssl.CertificateManager
import java.net.Authenticator
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Shares one HTTP client per (server, token) across the Tasks connector, editors and actions.
 * A new token for the same server replaces (and closes) the previous client.
 */
@Service(Service.Level.APP)
class OpenProjectClientPool : Disposable {

    private data class Key(val baseUrl: String, val tokenHash: String)

    private val connections = ConcurrentHashMap<Key, OpenProjectConnection>()

    fun connection(baseUrl: String, token: String?): OpenProjectConnection {
        val normalized = OpenProjectUrls.normalizeBaseUrl(baseUrl)
        val key = Key(normalized, hash(token.orEmpty()))
        connections[key]?.let { return it }
        synchronized(this) {
            connections[key]?.let { return it }
            connections.keys.filter { it.baseUrl == normalized }.forEach { stale -> connections.remove(stale)?.close() }
            val connection = OpenProjectConnection(OpenProjectApi(OpenProjectHttpClient.create(clientConfig(normalized, token)), normalized))
            connections[key] = connection
            return connection
        }
    }

    override fun dispose() {
        connections.values.forEach { it.close() }
        connections.clear()
    }

    private fun clientConfig(baseUrl: String, token: String?) = OpenProjectClientConfig(
        baseUrl = baseUrl,
        apiToken = token,
        userAgent = userAgent(),
        sslContext = runCatching { CertificateManager.getInstance().sslContext }.getOrNull(),
        authenticator = Authenticator.getDefault(),
    )

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        fun getInstance(): OpenProjectClientPool = service()

        /** The plugin version, stamped into plugin.properties by the build. */
        private val pluginVersion: String by lazy {
            runCatching {
                OpenProjectClientPool::class.java.getResourceAsStream("plugin.properties")?.use { Properties().apply { load(it) }.getProperty("version") }
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "dev"
        }

        fun userAgent(): String {
            val version = pluginVersion
            val build = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
            return "OpenProject-IntelliJ-Plugin/$version ($build)"
        }
    }
}
