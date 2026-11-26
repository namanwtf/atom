package atom.general.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import atom.general.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** The verified APK is being handed to Android's installer. */
    data object Installing : InstallState

    /** Android needs "Install unknown apps" allowed for Atom; the settings page was opened. */
    data object NeedsPermission : InstallState

    /**
     * A committed install session waits for the user to confirm. The confirmation screen opens from About (on
     * resume, or with "Continue update") or from the notification; no second download starts meanwhile.
     */
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
    private const val DIR = "atom4_updates"
    private const val CHANNEL = "atom4_updates"
    private const val NOTIFICATION_ID = 4_0001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    /** The installer's confirmation screen for the pending session, kept until the session ends. */
    @Volatile private var confirm: Intent? = null

    /** True once the confirmation was opened from the foreground, so resuming About does not reopen it. */
    @Volatile private var confirmOpened = false

    /** Download, check or session in progress: a tap must not start another one. */
    val busy: Boolean get() = state.value.let {
        it is InstallState.Downloading || it is InstallState.Verifying || it is InstallState.Installing ||
            it is InstallState.WaitingForUser
    }

    /** Whether this build may install releases over itself. Debug builds (`.debug`) never can. */
    fun canSelfUpdate(context: Context): Boolean = Releases.canSelfUpdate(context.packageName)

    /**
     * Starts the update to [apk]. While a session waits for confirmation, this reopens the confirmation instead of
     * downloading again. Called on the main thread.
     */
    fun start(context: Context, apk: ReleaseApk) {
        val app = context.applicationContext
        if (state.value is InstallState.WaitingForUser) {
            openConfirm(context)
            return
        }
        if (busy) return
        if (!canSelfUpdate(app)) {
            _state.value = InstallState.Failed("this build cannot update itself; use the release page")
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = InstallState.NeedsPermission
            openInstallPermission(app)
            return
        }
        // Set before the coroutine starts, so a second tap sees "busy" at once.
        _state.value = InstallState.Downloading(0, apk.size)
        scope.launch {
            val file = try {
                val f = download(app, apk)
                _state.value = InstallState.Verifying
                verifyApk(app, f)
                f
            } catch (e: UpdateError) {
                _state.value = InstallState.Failed(e.message ?: "unknown error")
                return@launch
            } catch (e: IOException) {
                _state.value = InstallState.Failed("download interrupted")
                return@launch
            } catch (e: Exception) {
                _state.value = InstallState.Failed("could not check the download")
                return@launch
            }
            try {
                install(app, file)
            } catch (e: Exception) {
                _state.value = InstallState.Failed("installer error: " + (e.message ?: e.javaClass.simpleName).take(200))
            }
        }
    }

    /** Clears a finished or failed attempt (not one in progress). */
    fun reset() {
        if (!busy) _state.value = InstallState.Idle
    }

    /**
     * Opens the pending confirmation. [onlyIfNotOpened] is for About resuming: the screen opens once on its own,
     * after that only from "Continue update" or the notification.
     */
    fun openConfirm(context: Context, onlyIfNotOpened: Boolean = false) {
        val intent = confirm ?: return
        if (state.value !is InstallState.WaitingForUser) return
        if (onlyIfNotOpened && confirmOpened) return
        confirmOpened = true
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            confirmOpened = false
            _state.value = InstallState.Failed("the installer did not open: " + (e.message ?: e.javaClass.simpleName).take(200))
        }
    }

    internal fun onPendingUserAction(context: Context, intent: Intent) {
        confirm = intent
        confirmOpened = false
        _state.value = InstallState.WaitingForUser
        notifyConfirm(context.applicationContext, intent)
    }

    internal fun onSessionEnded(context: Context, state: InstallState) {
        confirm = null
        confirmOpened = false
        cancelNotification(context.applicationContext)
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
        // Sessions an earlier process left behind (killed while waiting) would only pile up.
        installer.mySessions.forEach { s -> runCatching { installer.abandonSession(s.sessionId) } }
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
                // Before commit: the status broadcast can arrive before commit() returns and must not be overwritten.
                _state.value = InstallState.Installing
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

    private fun notifyConfirm(context: Context, confirm: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID, Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
                .setColor(android.graphics.Color.BLACK)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun cancelNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
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
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    UpdateInstaller.onSessionEnded(context, InstallState.Failed("the installer did not open"))
                } else {
                    // Opened by About (in the foreground) or the notification, never from here: a background
                    // activity start can be dropped silently.
                    UpdateInstaller.onPendingUserAction(context, confirm)
                }
            }
            // On success Android restarts the app into the new version; this rarely gets to run.
            PackageInstaller.STATUS_SUCCESS -> UpdateInstaller.onSessionEnded(context, InstallState.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                UpdateInstaller.onSessionEnded(context, InstallState.Failed("update cancelled"))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(200)
                UpdateInstaller.onSessionEnded(context, InstallState.Failed(message ?: "the installer refused the update"))
            }
        }
    }

    /** Typed getParcelableExtra only from API 34: the API 33 version has a known bug (what IntentCompat does). */
    private fun confirmIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 34) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }
}
