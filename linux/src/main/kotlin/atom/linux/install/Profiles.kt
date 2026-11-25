package atom.linux.install

import atom.linux.Distro
import kotlinx.serialization.Serializable

/** Optional software bundles installed after the base tools. */
@Serializable
enum class Profile(val title: String, val approxBytes: Long) {
    /** uv plus the latest stable CPython it manages. */
    Python("Python (uv)", 220_000_000L),

    /** Node.js LTS and npm. */
    Node("Node.js + npm", 230_000_000L),

    /** The full OpenSSH server suite (sshd, sftp-server, keys). */
    SshServer("OpenSSH server", 15_000_000L),

    /** OpenJDK 25, JADX, Apktool, smali/baksmali. */
    ApkAnalysis("APK analysis", 768_000_000L),
}

/** One unit of work the installer runs; a plan is an ordered list of these. */
sealed interface InstallStep {
    val key: String
    val title: String

    data object Base : InstallStep {
        override val key = "base"
        override val title = "Base system"
    }

    data object Tools : InstallStep {
        override val key = "tools"
        override val title = "Base tools"
    }

    data class ProfileStep(val profile: Profile) : InstallStep {
        override val key = "profile:${profile.name}"
        override val title = profile.title
    }

    /** Upgrades distro packages and re-runs the installed profiles' installers (which fetch the latest). */
    data object Upgrade : InstallStep {
        override val key = "upgrade"
        override val title = "Upgrading tools"
    }

    /** Rewrites Atom's config files, recovers half-configured packages and reinstalls missing base tools. */
    data object Repair : InstallStep {
        override val key = "repair"
        override val title = "Repairing"
    }

    companion object {
        fun parse(key: String): InstallStep? = when {
            key == Base.key -> Base
            key == Tools.key -> Tools
            key == Upgrade.key -> Upgrade
            key == Repair.key -> Repair
            key.startsWith("profile:") -> Profile.entries.firstOrNull { it.name == key.removePrefix("profile:") }?.let(::ProfileStep)
            else -> null
        }
    }
}

/**
 * The shell scripts that run inside the distro for each step. Every download is checked: distro packages by
 * apk/apt signatures; GitHub release assets against the sha256 digest GitHub publishes for the asset; Node.js
 * against the SHASUMS256.txt of its release.
 */
internal object InstallScripts {

    /** Common commands the base tools stage provides, checked by the health check too. */
    val BASE_COMMANDS = listOf("rg", "fd", "git", "ssh", "curl", "jq", "sqlite3", "file", "xz", "unzip", "less", "nano")

    fun approxToolsBytes(distro: Distro) = if (distro == Distro.Alpine) 70_000_000L else 180_000_000L

    fun tools(distro: Distro): String = when (distro) {
        Distro.Alpine -> """
            |set -e
            |apk update
            |apk add --no-progress ca-certificates bash ripgrep fd git openssh-client-default curl jq sqlite file xz unzip less nano htop procps tar
            |""".trimMargin()
        Distro.Debian -> """
            |set -e
            |export DEBIAN_FRONTEND=noninteractive
            |apt-get update
            |apt-get install -y --no-install-recommends ca-certificates ripgrep fd-find git openssh-client curl jq sqlite3 file xz-utils unzip less nano htop procps
            |ln -sf /usr/bin/fdfind /usr/local/bin/fd
            |""".trimMargin()
    }

    fun upgrade(distro: Distro): String = when (distro) {
        Distro.Alpine -> "set -e\napk update\napk upgrade --no-progress\n"
        Distro.Debian -> "set -e\nexport DEBIAN_FRONTEND=noninteractive\napt-get update\napt-get -y upgrade\n"
    }

    fun repair(distro: Distro): String = when (distro) {
        Distro.Alpine -> "apk fix --no-progress || true\n"
        Distro.Debian -> """
            |export DEBIAN_FRONTEND=noninteractive
            |dpkg --configure -a
            |apt-get -f install -y
            |apt-get update || true
            |""".trimMargin()
    }

    /** Shell helper: downloads a GitHub release asset and checks GitHub's published sha256 digest. */
    private val GITHUB_FETCH = """
gh_fetch() { # repo, asset-name regex, output file
  meta=${'$'}(curl -fsSL -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/${'$'}1/releases/latest")
  url=${'$'}(printf '%s' "${'$'}meta" | jq -r --arg re "${'$'}2" '[.assets[] | select(.name|test(${'$'}re))][0].browser_download_url')
  sum=${'$'}(printf '%s' "${'$'}meta" | jq -r --arg re "${'$'}2" '[.assets[] | select(.name|test(${'$'}re))][0].digest' | sed 's/^sha256://')
  tag=${'$'}(printf '%s' "${'$'}meta" | jq -r .tag_name)
  [ -n "${'$'}url" ] && [ "${'$'}url" != null ] || { echo "no release asset matching ${'$'}2 in ${'$'}1" >&2; return 1; }
  [ -n "${'$'}sum" ] && [ "${'$'}sum" != null ] || { echo "GitHub publishes no checksum for ${'$'}url; refusing to install it" >&2; return 1; }
  echo "Downloading ${'$'}url"
  curl -fL --retry 3 -o "${'$'}3" "${'$'}url"
  echo "${'$'}sum  ${'$'}3" | sha256sum -c -
  echo "${'$'}tag" > "${'$'}3.version"
}
"""

    fun profile(profile: Profile, distro: Distro): String = when (profile) {
        Profile.Python -> """
            |set -e
            |$GITHUB_FETCH
            |${prereqs(distro)}
            |tmp=${'$'}(mktemp -d)
            |gh_fetch astral-sh/uv '^uv-aarch64-unknown-linux-musl\.tar\.gz${'$'}' "${'$'}tmp/uv.tgz"
            |tar -xzf "${'$'}tmp/uv.tgz" -C "${'$'}tmp"
            |install -m 755 "${'$'}tmp"/uv-*/uv "${'$'}tmp"/uv-*/uvx /usr/local/bin/
            |rm -rf "${'$'}tmp"
            |export UV_PYTHON_INSTALL_DIR=/opt/uv/python UV_PYTHON_BIN_DIR=/usr/local/bin
            |mkdir -p /etc/profile.d
            |printf 'export UV_PYTHON_INSTALL_DIR=/opt/uv/python\nexport UV_PYTHON_BIN_DIR=/usr/local/bin\n' > /etc/profile.d/uv.sh
            |uv python install --default || uv python install
            |py=${'$'}(uv python find)
            |ln -sf "${'$'}py" /usr/local/bin/python3
            |ln -sf "${'$'}py" /usr/local/bin/python
            |python3 --version
            |""".trimMargin()
        Profile.Node -> when (distro) {
            // Alpine's nodejs package is the current LTS line, built for musl (nodejs.org has no musl arm64 build).
            Distro.Alpine -> "set -e\napk add --no-progress nodejs npm\nnode --version\nnpm --version\n"
            Distro.Debian -> """
                |set -e
                |${prereqs(distro)}
                |v=${'$'}(curl -fsSL https://nodejs.org/dist/index.json | jq -r '[.[] | select(.lts != false)][0].version')
                |f="node-${'$'}v-linux-arm64.tar.xz"
                |tmp=${'$'}(mktemp -d)
                |curl -fL --retry 3 -o "${'$'}tmp/${'$'}f" "https://nodejs.org/dist/${'$'}v/${'$'}f"
                |curl -fsSL "https://nodejs.org/dist/${'$'}v/SHASUMS256.txt" | grep " ${'$'}f${'$'}" > "${'$'}tmp/sum"
                |(cd "${'$'}tmp" && sha256sum -c sum)
                |rm -rf /opt/node && mkdir -p /opt/node
                |tar -xJf "${'$'}tmp/${'$'}f" -C /opt/node --strip-components=1
                |rm -rf "${'$'}tmp"
                |for b in node npm npx corepack; do ln -sf /opt/node/bin/${'$'}b /usr/local/bin/${'$'}b; done
                |node --version
                |npm --version
                |""".trimMargin()
        }
        Profile.SshServer -> """
            |set -e
            |${if (distro == Distro.Alpine) "apk add --no-progress openssh openssh-sftp-server" else "export DEBIAN_FRONTEND=noninteractive\napt-get install -y --no-install-recommends openssh-server openssh-sftp-server"}
            |ssh-keygen -A
            |mkdir -p /etc/ssh/sshd_config.d /root/.ssh /run/sshd
            |chmod 700 /root/.ssh
            |grep -q '^Include /etc/ssh/sshd_config.d' /etc/ssh/sshd_config || sed -i '1i Include /etc/ssh/sshd_config.d/*.conf' /etc/ssh/sshd_config
            |cat > /etc/ssh/sshd_config.d/atom.conf <<'CONF'
            |# Written by Atom. Unprivileged port; keys only for root (add yours to /root/.ssh/authorized_keys).
            |Port 8022
            |PermitRootLogin prohibit-password
            |PasswordAuthentication no
            |CONF
            |/usr/sbin/sshd -t
            |echo "Start it with: /usr/sbin/sshd -D -e (port 8022), or ask Atom to run it as a daemon."
            |""".trimMargin()
        Profile.ApkAnalysis -> """
            |set -e
            |$GITHUB_FETCH
            |${if (distro == Distro.Alpine) "apk add --no-progress openjdk25-jre-headless" else "export DEBIAN_FRONTEND=noninteractive\napt-get install -y --no-install-recommends openjdk-25-jre-headless smali"}
            |${prereqs(distro)}
            |mkdir -p /opt/apktools
            |tmp=${'$'}(mktemp -d)
            |gh_fetch skylot/jadx '^jadx-[0-9.]+\.zip${'$'}' "${'$'}tmp/jadx.zip"
            |rm -rf /opt/apktools/jadx && mkdir -p /opt/apktools/jadx
            |unzip -q "${'$'}tmp/jadx.zip" -d /opt/apktools/jadx
            |chmod +x /opt/apktools/jadx/bin/*
            |ln -sf /opt/apktools/jadx/bin/jadx /usr/local/bin/jadx
            |gh_fetch iBotPeaches/Apktool '^apktool_[0-9.]+\.jar${'$'}' /opt/apktools/apktool.jar
            |printf '#!/bin/sh\nexec java -jar /opt/apktools/apktool.jar "${'$'}@"\n' > /usr/local/bin/apktool
            |chmod +x /usr/local/bin/apktool
            |if ! command -v baksmali >/dev/null 2>&1; then
            |  # Apktool bundles smali/baksmali; expose their command-line entry points.
            |  printf '#!/bin/sh\nexec java -cp /opt/apktools/apktool.jar com.android.tools.smali.smali.Main "${'$'}@"\n' > /usr/local/bin/smali
            |  printf '#!/bin/sh\nexec java -cp /opt/apktools/apktool.jar com.android.tools.smali.baksmali.Main "${'$'}@"\n' > /usr/local/bin/baksmali
            |  chmod +x /usr/local/bin/smali /usr/local/bin/baksmali
            |fi
            |rm -rf "${'$'}tmp"
            |java -version
            |jadx --version
            |apktool --version
            |baksmali --version || echo "warning: baksmali did not start" >&2
            |echo "Note: rebuilding APKs with Apktool is not supported here (no aapt2 for this platform)."
            |""".trimMargin()
    }

    /** Commands a profile's installer itself needs (normally present after the base tools stage). */
    private fun prereqs(distro: Distro) = when (distro) {
        Distro.Alpine -> "command -v curl >/dev/null && command -v jq >/dev/null || apk add --no-progress curl jq ca-certificates unzip"
        Distro.Debian -> "command -v curl >/dev/null && command -v jq >/dev/null || { export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends curl jq ca-certificates unzip xz-utils; }"
    }

    /** Prints the installed version of a profile (used by Upgrade and the status card). */
    fun versionProbe(profile: Profile): String = when (profile) {
        Profile.Python -> "python3 --version 2>&1"
        Profile.Node -> "node --version 2>&1"
        Profile.SshServer -> "ssh -V 2>&1"
        Profile.ApkAnalysis -> "jadx --version 2>&1 | tail -1"
    }
}

/**
 * Turns package-manager output into a 0..1 fraction for a step: apk prints "(12/85) Installing ...", apt prints
 * "N newly installed" and then one "Setting up" / "Unpacking" line per package.
 */
class PackageProgress {
    private var total = 0
    private var done = 0
    private var fraction = 0f

    /** Feeds one output line; returns the new fraction or null when the line says nothing about progress. */
    fun line(text: String): Float? {
        APK.find(text)?.let { m ->
            val i = m.groupValues[1].toInt()
            val n = m.groupValues[2].toInt().coerceAtLeast(1)
            return update(i.toFloat() / n)
        }
        APT_TOTAL.find(text)?.let { m ->
            total = m.groupValues[1].toInt() + m.groupValues[2].toInt()
            return null
        }
        if (total > 0 && (text.startsWith("Unpacking ") || text.startsWith("Setting up "))) {
            done++
            // Each package is unpacked and then set up: two lines per package.
            return update(done.toFloat() / (total * 2))
        }
        return null
    }

    private fun update(f: Float): Float {
        fraction = maxOf(fraction, f.coerceIn(0f, 1f))
        return fraction
    }

    companion object {
        private val APK = Regex("""^\((\d+)/(\d+)\)\s""")
        private val APT_TOTAL = Regex("""(\d+) upgraded, (\d+) newly installed""")
    }
}
