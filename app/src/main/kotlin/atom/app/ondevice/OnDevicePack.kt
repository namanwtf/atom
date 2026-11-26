package atom.app.ondevice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import atom.core.ondevice.OnDeviceEngines
import atom.general.GeneralConfig
import atom.general.update.ReleaseFileException
import atom.general.update.ReleaseFiles
import atom.general.update.Releases
import atom.general.update.UpdateVerify
import dalvik.system.DexClassLoader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the optional on-device AI pack stands on this phone. */
sealed interface PackState {
    /** The start-up check has not finished yet. */
    data object Checking : PackState
    data object Missing : PackState
    data class Downloading(val done: Long, val total: Long) : PackState {
        val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
    }
    data object Verifying : PackState

    /** Installed and checked. [engines] is filled once the pack has been loaded in this process. */
    data class Ready(val bytes: Long, val engines: List<String>? = null) : PackState

    /** Installed for another app version (the app was updated): it is not loaded until it is updated too. */
    data class Outdated(val packVersion: Long) : PackState
    data class Failed(val reason: String) : PackState
}

/** What this version's GitHub Release offers, for the size shown before downloading. */
sealed interface PackOffer {
    data object Unknown : PackOffer
    data class Available(val bytes: Long) : PackOffer
    data object NotPublished : PackOffer
}

/**
 * The optional "on-device AI pack": a signed APK (module `:ondevice-pack`, never installed) with the on-device LLM
 * engines, the wake-word runtime and models. The app carries none of them, which keeps it small; a user who wants
 * offline models or "Hey Atom" downloads the pack once, from this repository's GitHub Release for the running
 * version only (the same code and checks as the app updater, no other hosts).
 *
 * Before the pack is used it must match its published SHA-256 and be signed with exactly the running app's
 * certificate and built for exactly the app's versionCode (an app update therefore asks for a pack update; until
 * then the old pack is not loaded, so the app never calls code built against other interfaces). The file is kept
 * read-only (Android 14 refuses writable dynamic code) and loaded by a [DexClassLoader] whose native library path
 * points into the APK: the libraries are stored uncompressed and page-aligned, so the system linker maps them
 * from the file and nothing is extracted. Every call goes through [OnDeviceEngines].
 *
 * Nothing here runs in the background: one file check at start-up, and network only when the user taps Download.
 */
object OnDevicePack {
    private const val TAG = "AtomPack"
    private const val DIR = "atom4_ondevice"
    private const val DOWNLOADS = "atom4_ondevice_download"
    private const val ABI = "arm64-v8a"

    private lateinit var app: Context
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PackState>(PackState.Checking)
    val state: StateFlow<PackState> = _state.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True while a checked pack is installed: on-device models and "Hey Atom" are offered only then. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _offer = MutableStateFlow<PackOffer>(PackOffer.Unknown)
    val offer: StateFlow<PackOffer> = _offer.asStateFlow()

    @Volatile private var loaded: Loaded? = null

    private class Loaded(val file: File, val engines: OnDeviceEngines)

    /** The pack's file while it is installed and checked. */
    @Volatile private var installed: File? = null

    /** Called once from `Application.onCreate`: a quick file check on an I/O thread, nothing is loaded. */
    fun init(context: Context, scope: CoroutineScope) {
        app = context.applicationContext
        scope.launch(Dispatchers.IO) { mutex.withLock { checkInstalled() } }
    }

    private val busy: Boolean get() = state.value.let { it is PackState.Downloading || it is PackState.Verifying }

    /**
     * The pack's engines, loading the pack on first use (a few hundred ms; call off the main thread). Null when no
     * checked pack is installed. Throws when the pack is installed but cannot be loaded.
     */
    fun engines(): OnDeviceEngines? {
        loaded?.let { return it.engines }
        val file = installed?.takeIf { _ready.value } ?: return null
        synchronized(this) {
            loaded?.let { return it.engines }
            val engines = load(file)
            loaded = Loaded(file, engines)
            val s = _state.value
            if (s is PackState.Ready) set(s.copy(engines = engines.engineNames))
            return engines
        }
    }

    /** Path of the loaded pack file, for the wake-word models (they are read from it). */
    fun loadedPath(): String? = loaded?.file?.path

    /** Loads the pack (if needed) so its screen can list the engines. Errors become [PackState.Failed]. */
    suspend fun describe() = withContext(Dispatchers.IO) {
        if (state.value !is PackState.Ready || loaded != null) return@withContext
        try {
            engines()
        } catch (e: Throwable) {
            Log.w(TAG, "pack failed to load", e)
            set(PackState.Failed("the pack could not be loaded: " + (e.message ?: e.javaClass.simpleName).take(200)))
        }
    }

    /** Loads each native library of the pack; one line per library. For diagnostics (and CI) only. */
    suspend fun probe(): List<String> = withContext(Dispatchers.IO) {
        try {
            engines()?.probeNative() ?: listOf("pack not installed")
        } catch (e: Throwable) {
            listOf("pack failed to load: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Size of this version's pack on GitHub, looked up when the pack screen opens (one request, then cached). */
    suspend fun refreshOffer() = withContext(Dispatchers.IO) {
        if (_offer.value != PackOffer.Unknown) return@withContext
        val version = appVersionName()
        try {
            val release = ReleaseFiles.releaseByTag(GeneralConfig.updateRepo, "v$version")
            val asset = release?.let { ReleaseFiles.asset(GeneralConfig.updateRepo, it, Releases.packAssetName(version)) }
            _offer.value = if (asset != null) PackOffer.Available(asset.size) else PackOffer.NotPublished
        } catch (e: Exception) {
            // Stays Unknown: the size is only a hint, and Download reports the real error.
        }
    }

    /** Downloads, checks and installs this version's pack. Safe to call twice: a running download is not repeated. */
    suspend fun download() = withContext(Dispatchers.IO) {
        if (busy) return@withContext
        set(PackState.Downloading(0, 0))
        mutex.withLock {
            try {
                val version = appVersionName()
                val repo = GeneralConfig.updateRepo.trim()
                val release = ReleaseFiles.releaseByTag(repo, "v$version")
                    ?: throw ReleaseFileException("Atom $version has no release on GitHub")
                val asset = ReleaseFiles.asset(repo, release, Releases.packAssetName(version))
                    ?: throw ReleaseFileException("Atom $version has no on-device AI pack")
                _offer.value = PackOffer.Available(asset.size)
                val file = ReleaseFiles.download(asset, File(app.cacheDir, DOWNLOADS)) { done, total ->
                    set(PackState.Downloading(done, total))
                }
                set(PackState.Verifying)
                installChecked(file)
            } catch (e: ReleaseFileException) {
                fail(e.message ?: "unknown error")
            } catch (e: IOException) {
                fail("download interrupted")
            } catch (e: Exception) {
                fail("could not check the download")
            }
        }
    }

    /**
     * Installs a pack file that is already on the phone. Debug builds only (CI pushes the debug pack this way); it
     * skips only the SHA-256 step, because there is no published checksum: the package, version and signing checks
     * still apply.
     */
    suspend fun installLocal(file: File): String = withContext(Dispatchers.IO) {
        if ((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return@withContext "refused: not a debug build"
        mutex.withLock {
            set(PackState.Verifying)
            val staged = File(File(app.cacheDir, DOWNLOADS).apply { mkdirs() }, "local.apk")
            file.copyTo(staged, overwrite = true)
            installChecked(staged)
        }
        state.value.toString()
    }

    /** Removes the pack and frees its space. Engines already loaded in this process stay in memory until it ends. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        if (busy) return@withContext
        mutex.withLock {
            installed = null
            loaded = null
            packDir().listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
            set(PackState.Missing)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun set(s: PackState) {
        _state.value = s
        _ready.value = s is PackState.Ready
    }

    private fun fail(reason: String) {
        File(app.cacheDir, DOWNLOADS).listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
        // A pack that was ready before a failed update attempt is still ready.
        val kept = installed
        if (kept != null && kept.isFile && check(kept) == UpdateVerify.PackCheck.Ok) {
            set(PackState.Ready(kept.length(), loaded?.engines?.engineNames))
        } else {
            set(PackState.Failed(reason))
        }
    }

    private fun packDir(): File = File(app.filesDir, DIR).apply { mkdirs() }

    private fun appVersionName(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun check(file: File): UpdateVerify.PackCheck {
        val self = ReleaseFiles.installedFacts(app) ?: return UpdateVerify.PackCheck.Unreadable
        return UpdateVerify.checkPack(self, ReleaseFiles.archiveFacts(app, file.path), OnDeviceEngines.PACK_PACKAGE)
    }

    /** Start-up: the newest pack file in the folder, checked against this app; anything else there is removed. */
    private fun checkInstalled() {
        val files = packDir().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".apk") }.sortedByDescending { it.name }
        files.drop(1).forEach { it.delete() }
        val file = files.firstOrNull()
        if (file == null) {
            set(PackState.Missing)
            return
        }
        when (val c = check(file)) {
            UpdateVerify.PackCheck.Ok -> {
                installed = file
                set(PackState.Ready(file.length()))
            }
            is UpdateVerify.PackCheck.WrongVersion -> set(PackState.Outdated(c.found))
            else -> {
                file.delete()
                set(PackState.Missing)
            }
        }
    }

    /** Checks [file] (a complete download), moves it into place read-only and loads it once to be sure. */
    private fun installChecked(file: File) {
        val reason = when (val c = check(file)) {
            UpdateVerify.PackCheck.Ok -> null
            UpdateVerify.PackCheck.Unreadable -> "the download is not a readable APK"
            is UpdateVerify.PackCheck.WrongPackage -> "the download is ${c.found}, not the on-device AI pack"
            is UpdateVerify.PackCheck.WrongVersion -> "the pack is for another Atom version (${c.found}, this is ${c.app})"
            UpdateVerify.PackCheck.SignerMismatch -> "the pack is not signed with this app's key"
        }
        if (reason != null) {
            file.delete()
            fail(reason)
            return
        }
        // A new name each time: a pack already loaded in this process keeps its own file (and native libraries).
        val target = File(packDir(), "pack-${System.currentTimeMillis()}.apk")
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        // Android 14+ loads dynamic code only from read-only files.
        target.setReadOnly()
        val engines = try {
            load(target)
        } catch (e: Throwable) {
            Log.w(TAG, "new pack failed to load", e)
            target.delete()
            fail("the pack could not be loaded: " + (e.message ?: e.javaClass.simpleName).take(200))
            return
        }
        packDir().listFiles().orEmpty().forEach { if (it.isFile && it != target) it.delete() }
        installed = target
        loaded = Loaded(target, engines)
        set(PackState.Ready(target.length(), engines.engineNames))
        Log.i(TAG, "on-device AI pack ready: ${engines.engineNames.joinToString()}")
    }

    private fun load(file: File): OnDeviceEngines {
        val loader = PackClassLoader(file.path, "${file.path}!/lib/$ABI", OnDevicePack::class.java.classLoader!!)
        val entry = Class.forName(OnDeviceEngines.ENTRY_CLASS, true, loader).getDeclaredConstructor().newInstance()
        return entry as? OnDeviceEngines ?: error("the pack's entry is not an on-device engine provider")
    }
}

/**
 * The pack's class loader. The types the pack shares with the app (Atom's core interfaces, Kotlin, kotlinx and the
 * platform) always come from the app, so both sides see the same classes; everything else is looked up in the pack
 * first, so a library the pack bundles never mixes with an unrelated class of the same name in the app.
 * Native libraries resolve to [libraryPath] inside the pack APK ([DexClassLoader.findLibrary]).
 */
private class PackClassLoader(apk: String, libraryPath: String, parent: ClassLoader) :
    DexClassLoader(apk, null, libraryPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (SHARED.any { name.startsWith(it) }) return super.loadClass(name, resolve)
        synchronized(this) {
            findLoadedClass(name)?.let { return it }
            return try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }
    }

    private companion object {
        val SHARED = listOf(
            "java.", "javax.", "android.", "dalvik.", "libcore.", "sun.", "org.json.", "org.xml.", "org.w3c.",
            "kotlin.", "kotlinx.", "atom.core.",
        )
    }
}
