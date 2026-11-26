package atom.llm.cloud

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * A one-shot HTTP listener on 127.0.0.1 that receives the OAuth redirect from the system browser. It binds
 * only for the duration of one sign-in and closes as soon as a matching callback arrives, the caller is
 * cancelled, or [timeoutMillis] elapses.
 */
class LoopbackCallback(private val port: Int, private val path: String = "/auth/callback") {

    data class Result(val code: String?, val state: String?, val error: String?, val errorDescription: String?)

    private var socket: ServerSocket? = null

    /** Binds now so a port clash is reported before the browser opens. */
    fun bind(): LoopbackCallback {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        } catch (e: IOException) {
            server.close()
            throw OAuthException("Port $port is in use by another app. Close it and try again.", OAuthException.Kind.Network)
        }
        socket = server
        return this
    }

    suspend fun await(timeoutMillis: Long): Result {
        val server = socket ?: bind().socket!!
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { cont ->
                    thread(name = "atom-oauth-callback", isDaemon = true) {
                        try {
                            while (true) {
                                val result = server.accept().use(::handle) ?: continue
                                if (cont.isActive) cont.resume(result)
                                return@thread
                            }
                        } catch (e: IOException) {
                            if (cont.isActive) {
                                cont.resumeWithException(OAuthException("Sign-in callback failed: ${e.message}", OAuthException.Kind.Network))
                            }
                        }
                    }
                    cont.invokeOnCancellation { runCatching { server.close() } }
                }
            }
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: java.net.Socket): Result? {
        client.soTimeout = 5_000
        val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val requestLine = reader.readLine() ?: return null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1) ?: return respond(client, 400, null)
        val requestPath = target.substringBefore('?')
        if (requestPath != path) return respond(client, 404, null)
        val query = parseQuery(target.substringAfter('?', ""))
        val result = Result(query["code"], query["state"], query["error"], query["error_description"])
        return respond(client, 200, result)
    }

    private fun respond(client: java.net.Socket, status: Int, result: Result?): Result? {
        val ok = result?.code != null && result.error == null
        val body = when {
            status != 200 -> "Not found"
            ok -> PAGE.format("Atom", RECEIVED)
            else -> PAGE.format("Atom", INCOMPLETE)
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
        return result
    }

    internal companion object {
        /** What the browser tab says once the provider redirected back. */
        const val RECEIVED = "Authorization received. Return to Atom to finish signing in."
        const val INCOMPLETE = "Authorization incomplete. Return to Atom and retry."

        private const val PAGE = "<!doctype html><html><head><meta name=viewport content='width=device-width'>" +
            "<title>Atom</title></head><body style='background:#000;color:#fff;font-family:sans-serif;padding:48px 24px'>" +
            "<h2>%s</h2><p style='color:#ddd;font-size:18px'>%s</p></body></html>"

        fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
    }
}
