package com.github.jpmand.openprojectideaintegraiton.core

import com.github.jpmand.openprojectideaintegraiton.OpenProjectBundle
import com.github.jpmand.openprojectideaintegraiton.api.OpenProjectApiException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The project-level coroutine scope of the plugin; editors and dialogs work in child scopes of it. */
@Service(Service.Level.PROJECT)
class OpenProjectProjectScope(val cs: CoroutineScope) {

    fun childScope(name: String): CoroutineScope =
        CoroutineScope(cs.coroutineContext + SupervisorJob(cs.coroutineContext.job) + CoroutineName(name))

    companion object {
        fun getInstance(project: Project): OpenProjectProjectScope = project.service()
    }
}

class NoServerConfiguredException : Exception(OpenProjectBundle.message("error.no.server"))

/** Resolves a configured server and its connection; reading the token happens off the EDT. */
suspend fun connect(project: Project, serverId: String?): Pair<OpenProjectServer, OpenProjectConnection> = withContext(Dispatchers.IO) {
    val server = OpenProjectServers.getInstance(project).find(serverId) ?: throw NoServerConfiguredException()
    server to server.repository.connection()
}

/** A message for the user explaining why an OpenProject call failed. */
fun describeError(error: Throwable): String = when (error) {
    is OpenProjectApiException -> error.message ?: OpenProjectBundle.message("error.unknown")
    is NoServerConfiguredException -> error.message!!
    is UnknownHostException, is ConnectException -> OpenProjectBundle.message("error.unreachable", error.message.orEmpty())
    is SSLException -> OpenProjectBundle.message("error.ssl", error.message.orEmpty())
    is IOException -> OpenProjectBundle.message("error.io", error.message ?: error.javaClass.simpleName)
    else -> error.message ?: error.javaClass.simpleName
}
