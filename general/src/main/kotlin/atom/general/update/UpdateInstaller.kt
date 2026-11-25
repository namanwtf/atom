package atom.general.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Progress of an in-app update the user started from About. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val done: Long, val total: Long) : InstallState {
        val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
    }
    data object Verifying : InstallState

    /** Android needs "Install unknown apps" allowed for Atom; the settings page was opened. */
    data object NeedsPermission : InstallState

    /** The system installer is showing its confirmation. */
    data object WaitingForUser : InstallState
    data class Failed(val reason: String) : InstallState
}

/**
 * Downloads a release APK from GitHub into the app cache, checks it, and hands it to Android's own installer.
 * Before the installer opens, the file must match the release's published SHA-256 AND be signed by the same
 * certificate as the running app (Android enforces the same rule; checking first never shows the installer a
 * bad file). Only github.com release downloads are fetched: no proxies, no telemetry, nothing runs in background.
 */
object UpdateInstaller {
    internal const val ACTION_STATUS = "atom.general.update.INSTALL_STATUS"
    private const val DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = state.value.let { it is InstallState.Downloading || it is InstallState.Verifying }

    /** Starts (or resumes after the permission screen) the update to [apk]. A second tap while busy is ignored. */
    fun start(context: Context, apk: ReleaseApk) {
        val app = context.applicationContext
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = InstallState.NeedsPermission
            openInstallPermission(app)
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = InstallState.Downloading(0, apk.size)
            _state.value = try {
                val file = download(app, apk)
                _state.value = InstallState.Verifying
                verifyApk(app, file)
                install(app, file)
                InstallState.WaitingForUser
            } catch (e: UpdateError) {
                InstallState.Failed(e.message ?: "unknown error")
            } catch (e: IOException) {
                InstallState.Failed("download interrupted")
            } catch (e: Exception) {
                InstallState.Failed("could not start the installer")
            }
        }
    }

    fun reset() {
        if (!busy) _state.value = InstallState.Idle
    }

    internal fun onStatus(state: InstallState) {
        _state.value = state
    }

    private class UpdateError(message: String) : Exception(message)

    private fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Cache dir for downloads. Holds plain files only, so a flat, non-recursive clean is enough. */
    private fun cleanDir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        dir.listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
        return dir
    }

    private fun download(context: Context, apk: ReleaseApk): File {
        val dir = cleanDir(context)
        val shaText = fetchSmall(apk.sha256Url)
        val expected = UpdateVerify.parseSha256(shaText, apk.name) ?: throw UpdateError("checksum file is unreadable")
        val part = File(dir, apk.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val conn = open(apk.url)
        try {
            if (conn.responseCode != 200) throw UpdateError("GitHub answered HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw UpdateError("insecure redirect")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > apk.size || done > Releases.MAX_APK_BYTES) throw UpdateError("download is larger than published")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            _state.value = InstallState.Downloading(done, apk.size)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (done != apk.size) {
            part.delete()
            throw UpdateError("download is incomplete")
        }
        if (!UpdateVerify.hashMatches(expected, UpdateVerify.hex(md.digest()))) {
            part.delete()
            throw UpdateError("checksum does not match")
        }
        val target = File(dir, apk.name)
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateError("could not save the download")
        }
        return target
    }

    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val installed = facts(installedInfo(pm, context.packageName))
            ?: throw UpdateError("could not read this app's signature")
        val candidate = facts(archiveInfo(pm, file.path))
        when (val c = UpdateVerify.check(installed, candidate)) {
            UpdateVerify.ApkCheck.Ok -> Unit
            UpdateVerify.ApkCheck.Unreadable -> fail(file, "the download is not a readable APK")
            is UpdateVerify.ApkCheck.WrongPackage -> fail(file, "the download is for ${c.found}, not this build")
            is UpdateVerify.ApkCheck.NotNewer -> fail(file, "the download is not newer than this version")
            UpdateVerify.ApkCheck.SignerMismatch -> fail(file, "the download is not signed with this app's key")
        }
    }

    private fun fail(file: File, reason: String): Nothing {
        file.delete()
        throw UpdateError(reason)
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
                // Mutable: the installer adds the status extras. Explicit component, so nothing else can receive it.
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        } finally {
            // The session holds its own copy now.
            file.delete()
        }
    }

    private fun installedInfo(pm: PackageManager, name: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun facts(info: PackageInfo?): UpdateVerify.ApkFacts? {
        if (info == null) return null
        val signing = info.signingInfo ?: return null
        fun digest(s: Signature) = UpdateVerify.sha256Hex(s.toByteArray())
        val current = signing.apkContentsSigners.orEmpty().map(::digest).toSet()
        val history = if (signing.hasMultipleSigners()) emptyList() else signing.signingCertificateHistory.orEmpty().map(::digest)
        return UpdateVerify.ApkFacts(info.packageName, info.longVersionCode, current, history)
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // github.com answers release downloads with a redirect to its own download host; https only.
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("User-Agent", "Atom-Android")
        return conn
    }

    private fun fetchSmall(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw UpdateError("GitHub answered HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw UpdateError("insecure redirect")
            return conn.inputStream.use { s ->
                val buf = ByteArray(4096)
                var len = 0
                while (len < buf.size) {
                    val n = s.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
                String(buf, 0, len, Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Receives the system installer's status for a session [UpdateInstaller] committed. Not exported. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    UpdateInstaller.onStatus(InstallState.Failed("the installer did not open"))
                    return
                }
                UpdateInstaller.onStatus(InstallState.WaitingForUser)
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { UpdateInstaller.onStatus(InstallState.Failed("the installer did not open")) }
            }
            // On success Android restarts the app into the new version; this rarely gets to run.
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.onStatus(InstallState.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstaller.onStatus(InstallState.Failed("update cancelled"))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(200)
                UpdateInstaller.onStatus(InstallState.Failed(message ?: "the installer refused the update"))
            }
        }
    }
}
