package atom.web.sites

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A tiny static-file server for one website project, for the live preview and the agent's own browser.
 *
 * - Listens on 127.0.0.1 only, on [preferredPort] when free (so a restarted preview keeps its address), otherwise a
 *   random free port. Every request must name that address in its Host header, so other sites in a browser cannot
 *   reach it through a rebound host name.
 * - Serves only regular files under [root]: "..", encoded variants, hidden names and symbolic links (anywhere on the
 *   way) are refused. GET and HEAD only, one request per connection, nothing cached (`Cache-Control: no-store`).
 * - Injects a small script into HTML pages that long-polls [RELOAD_PATH] and reloads the page when [changed] is called.
 * - Stops itself when no screen holds it ([acquire] / [release]) and nothing but the reload poll asked for a file for
 *   [idleTimeoutMs]. While nobody uses it, its one thread sleeps in `accept`; nothing polls.
 *
 * Pure JVM (no Android), so it is unit tested as is.
 */
class SiteServer(
    val root: File,
    private val preferredPort: Int = 0,
    private val idleTimeoutMs: Long = 60_000,
    private val acceptTimeoutMs: Int = 15_000,
    private val onStopped: (SiteServer) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val versionChanged = lock.newCondition()
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val connections = Semaphore(MAX_CONNECTIONS)
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private val leases = AtomicInteger(0)

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var version: Long = 1
        private set

    @Volatile
    private var lastActivity = System.currentTimeMillis()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    val url: String get() = "http://127.0.0.1:$port/"

    /** Binds and starts serving. Returns this; throws [IOException] when no port can be bound. */
    fun start(): SiteServer = lock.withLock {
        if (running) return this
        val loopback = InetAddress.getByName("127.0.0.1")
        val s = preferredPort.takeIf { it in 1..65535 }?.let { bind(loopback, it) } ?: bind(loopback, 0)
            ?: throw IOException("No free port on 127.0.0.1")
        begin(s)
    }

    private fun bind(address: InetAddress, port: Int): ServerSocket? {
        val s = ServerSocket()
        return try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(address, port), BACKLOG)
            s
        } catch (e: IOException) {
            runCatching { s.close() }
            null
        }
    }

    private fun begin(s: ServerSocket): SiteServer {
        s.soTimeout = acceptTimeoutMs
        socket = s
        port = s.localPort
        running = true
        lastActivity = System.currentTimeMillis()
        val threads = AtomicInteger()
        pool = Executors.newCachedThreadPool { r -> Thread(r, "atom-site-${threads.incrementAndGet()}").apply { isDaemon = true } }
        Thread({ acceptLoop(s) }, "atom-site-accept-$port").apply { isDaemon = true }.start()
        return this
    }

    /** A screen shows this site: the server keeps running until [release]. */
    fun acquire() {
        leases.incrementAndGet()
        touch()
    }

    /** The screen went away. With no other holder and nothing asked for a file lately, the server stops now. */
    fun release(stopIfIdleForMs: Long = 10_000) {
        if (leases.decrementAndGet() <= 0) {
            leases.set(0)
            if (System.currentTimeMillis() - lastActivity > stopIfIdleForMs) stop()
        }
    }

    val holders: Int get() = leases.get()

    /** Counts as use (a tool call, opening the site elsewhere), so the idle timer starts again. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    /** The site's files changed: pages that are open reload. */
    fun changed() {
        lock.withLock {
            version++
            versionChanged.signalAll()
        }
    }

    fun stop() {
        val s: ServerSocket? = lock.withLock {
            if (!running) return
            running = false
            val current = socket
            socket = null
            versionChanged.signalAll()
            current
        }
        runCatching { s?.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        pool = null
        onStopped(this)
    }

    private fun idle(): Boolean = leases.get() == 0 && System.currentTimeMillis() - lastActivity > idleTimeoutMs

    private fun acceptLoop(s: ServerSocket) {
        try {
            while (running) {
                val client = try {
                    s.accept()
                } catch (e: SocketTimeoutException) {
                    if (idle()) stop()
                    continue
                } catch (e: IOException) {
                    break
                }
                if (!connections.tryAcquire()) {
                    runCatching {
                        client.soTimeout = 2_000
                        respond(client.getOutputStream(), 503, "Service Unavailable", "text/plain; charset=utf-8", "Busy".toByteArray(), head = false)
                        client.close()
                    }
                    continue
                }
                open += client
                val p = pool
                if (p == null) {
                    finish(client)
                    continue
                }
                runCatching {
                    p.execute {
                        // Nothing a client sends may escape this thread: an uncaught exception would end the app.
                        try {
                            handle(client)
                        } catch (e: Throwable) {
                            Unit
                        } finally {
                            finish(client)
                        }
                    }
                }.onFailure { finish(client) }
                if (idle()) stop()
            }
        } catch (e: Throwable) {
            Unit
        } finally {
            if (running) stop()
        }
    }

    private fun finish(client: Socket) {
        open -= client
        runCatching { client.close() }
        connections.release()
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream()
        val out = BufferedOutputStream(client.getOutputStream())
        val head = try {
            readHead(input)
        } catch (e: IOException) {
            null
        } ?: run {
            respondText(out, 400, "Bad Request", "Bad request")
            return
        }
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            respondText(out, 400, "Bad Request", "Bad request")
            return
        }
        val (method, target, _) = requestLine
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        if (!hostAllowed(headers["host"])) {
            respondText(out, 403, "Forbidden", "Forbidden")
            return
        }
        if (method != "GET" && method != "HEAD") {
            respond(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Only GET and HEAD".toByteArray(), head = false, extra = listOf("Allow" to "GET, HEAD"))
            return
        }
        val isHead = method == "HEAD"
        val (path, query) = splitTarget(target) ?: run {
            respondText(out, 400, "Bad Request", "Bad request")
            return
        }
        if (path == RELOAD_PATH) {
            reload(out, query, isHead)
            return
        }
        touch()
        when (val r = resolve(path)) {
            is Resolved.Refused -> respondText(out, r.status, r.reason, r.reason, isHead)
            is Resolved.Redirect -> respond(out, 301, "Moved Permanently", "text/plain; charset=utf-8", ByteArray(0), isHead, listOf("Location" to r.location))
            is Resolved.Missing -> respond(out, 404, "Not Found", "text/html; charset=utf-8", inject(missingPage(path)), isHead)
            is Resolved.Found -> serveFile(out, r.file, isHead)
        }
    }

    private fun hostAllowed(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h == "127.0.0.1:$port" || h == "localhost:$port"
    }

    private fun serveFile(out: OutputStream, file: File, head: Boolean) {
        val type = SitePaths.mimeOf(file.name)
        val length = file.length()
        if (SitePaths.isHtml(file.name) && length <= MAX_INJECT) {
            val body = runCatching { file.readBytes() }.getOrElse {
                respondText(out, 404, "Not Found", "Not found", head)
                return
            }
            respond(out, 200, "OK", type, inject(body), head)
            return
        }
        val stream = runCatching { file.inputStream() }.getOrElse {
            respondText(out, 404, "Not Found", "Not found", head)
            return
        }
        stream.use {
            writeHead(out, 200, "OK", type, length, emptyList())
            if (!head) it.copyTo(out, 64 * 1024)
            out.flush()
        }
    }

    /** `GET /__atom_reload` answers `{"version":N}` at once; `?since=N` waits (up to [LONG_POLL_MS]) for a newer one. */
    private fun reload(out: OutputStream, query: String, head: Boolean) {
        val since = query.split('&').firstOrNull { it.startsWith("since=") }?.removePrefix("since=")?.toLongOrNull()
        if (since != null) {
            val deadline = System.currentTimeMillis() + LONG_POLL_MS
            lock.withLock {
                while (running && version == since) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    versionChanged.await(left, TimeUnit.MILLISECONDS)
                }
            }
        }
        respond(out, 200, "OK", "application/json; charset=utf-8", "{\"version\":$version}".toByteArray(), head)
    }

    sealed interface Resolved {
        data class Found(val file: File) : Resolved
        data class Redirect(val location: String) : Resolved
        data object Missing : Resolved
        data class Refused(val status: Int, val reason: String) : Resolved
    }

    /** Maps a URL path (still percent-encoded, starting with "/") to a file under [root]. */
    fun resolve(rawPath: String): Resolved {
        val decoded = percentDecode(rawPath) ?: return Resolved.Refused(400, "Bad Request")
        if (!decoded.startsWith("/") || '\u0000' in decoded || '\\' in decoded) return Resolved.Refused(400, "Bad Request")
        val segments = ArrayList<String>()
        for (s in decoded.split('/')) {
            when {
                s.isEmpty() || s == "." -> Unit
                s == ".." -> return Resolved.Refused(403, "Forbidden")
                s.startsWith(".") -> return Resolved.Missing
                else -> segments += s
            }
        }
        if (SitePaths.isLink(root)) return Resolved.Refused(403, "Forbidden")
        var f = root
        for (s in segments) {
            f = File(f, s)
            if (SitePaths.isLink(f)) return Resolved.Refused(403, "Forbidden")
        }
        val base = root.canonicalPath
        val canonical = f.canonicalPath
        if (canonical != base && !canonical.startsWith(base + File.separator)) return Resolved.Refused(403, "Forbidden")
        if (SitePaths.isDirectory(f)) {
            if (!rawPath.endsWith("/")) return Resolved.Redirect("$rawPath/")
            val index = File(f, "index.html")
            if (SitePaths.isLink(index)) return Resolved.Refused(403, "Forbidden")
            return if (SitePaths.isRegularFile(index)) Resolved.Found(index) else Resolved.Missing
        }
        return if (SitePaths.isRegularFile(f)) Resolved.Found(f) else Resolved.Missing
    }

    private fun missingPage(path: String): ByteArray {
        val safe = path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return (
            "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Not found</title><style>body{font-family:sans-serif;margin:2rem;color:#666}</style></head>" +
                "<body><p>Nothing here yet: $safe</p></body></html>"
            ).toByteArray()
    }

    private fun respondText(out: OutputStream, status: Int, reason: String, text: String, head: Boolean = false) =
        respond(out, status, reason, "text/plain; charset=utf-8", text.toByteArray(), head)

    private fun respond(
        out: OutputStream,
        status: Int,
        reason: String,
        type: String,
        body: ByteArray,
        head: Boolean,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        runCatching {
            writeHead(out, status, reason, type, body.size.toLong(), extra)
            if (!head) out.write(body)
            out.flush()
        }
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, type: String, length: Long, extra: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        const val RELOAD_PATH = "/__atom_reload"
        const val LONG_POLL_MS = 25_000L
        private const val MAX_CONNECTIONS = 24
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_INJECT = 8L * 1024 * 1024

        /** Reloads the page once the site changes; quiet when the server is gone (the preview closed). */
        const val RELOAD_SCRIPT =
            "<script data-atom-reload>(function(){var v=null,f=0;function p(){fetch('$RELOAD_PATH'+(v===null?'':'?since='+v)," +
                "{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){f=0;if(v!==null&&d.version!==v){location.reload();return}" +
                "v=d.version;p()}).catch(function(){if(++f<20)setTimeout(p,3000)})}p()})();</script>"

        /** [html] with [RELOAD_SCRIPT] before its last `</body>` (or `</html>`, or at the end). */
        fun inject(html: ByteArray): ByteArray {
            val script = RELOAD_SCRIPT.toByteArray()
            val at = lastIndexOfIgnoreCase(html, "</body>").takeIf { it >= 0 }
                ?: lastIndexOfIgnoreCase(html, "</html>").takeIf { it >= 0 }
                ?: html.size
            val out = ByteArrayOutputStream(html.size + script.size)
            out.write(html, 0, at)
            out.write(script)
            out.write(html, at, html.size - at)
            return out.toByteArray()
        }

        private fun lastIndexOfIgnoreCase(hay: ByteArray, needle: String): Int {
            val n = needle.lowercase().toByteArray(Charsets.US_ASCII)
            var i = hay.size - n.size
            while (i >= 0) {
                var j = 0
                while (j < n.size) {
                    var b = hay[i + j].toInt() and 0xff
                    if (b in 'A'.code..'Z'.code) b += 32
                    if (b != n[j].toInt()) break
                    j++
                }
                if (j == n.size) return i
                i--
            }
            return -1
        }

        /** Path and query of a request target in origin form ("/a?b") or absolute form ("http://host/a?b"). */
        fun splitTarget(target: String): Pair<String, String>? {
            var t = target
            if (t.startsWith("http://") || t.startsWith("https://")) {
                val afterScheme = t.substringAfter("://")
                val slash = afterScheme.indexOf('/')
                t = if (slash < 0) "/" else afterScheme.substring(slash)
            }
            if (!t.startsWith("/")) return null
            t = t.substringBefore('#')
            val q = t.indexOf('?')
            return if (q < 0) t to "" else t.substring(0, q) to t.substring(q + 1)
        }

        /** Strict percent-decoding into UTF-8: a bad escape or invalid UTF-8 gives null. "+" stays "+". */
        fun percentDecode(s: String): String? {
            val bytes = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    if (c.code > 0x7f) bytes.write(c.toString().toByteArray(Charsets.UTF_8)) else bytes.write(c.code)
                    i++
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }

        /** The request line and headers, up to the blank line; null when the client closed early. */
        internal fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                if (buf.size() > MAX_HEAD) throw IOException("request head too large")
                state = when (b) {
                    '\r'.code -> if (state == 2) 3 else 1
                    '\n'.code -> if (state == 1) 2 else if (state == 3) 4 else 0
                    else -> 0
                }
                if (state == 4) break
            }
            return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
    }
}
