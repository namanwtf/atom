package atom.device.shell

import atom.device.ShellTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real session protocol against the host's /bin/sh. */
class PersistentShellTest {
    private fun shell(idleMs: Long = 120_000) = PersistentShell(ShellTier.Root, idleMs) { ProcessBuilder("/bin/sh").start() }

    @Test
    fun separatesStdoutStderrAndExitCode() = runBlocking {
        val sh = shell()
        val r = sh.run("echo out; echo err >&2; exit 3")
        assertEquals(3, r.exitCode)
        assertEquals("out", r.stdout)
        assertEquals("err", r.stderr)
        sh.close()
    }

    @Test
    fun reusesOneProcessAcrossCommandsAndSurvivesExitAndSyntaxErrors() = runBlocking {
        val sh = shell()
        assertEquals("1", sh.run("echo 1").stdout)
        assertEquals(0, sh.run("exit 0").exitCode)
        val bad = sh.run("if then fi")
        assertTrue(bad.exitCode != 0, "syntax error should fail: $bad")
        assertEquals("still here", sh.run("echo still here").stdout)
        assertEquals(1, sh.spawnCount)
        sh.close()
    }

    @Test
    fun quotingAndMultilineOutput() = runBlocking {
        val sh = shell()
        val r = sh.run("printf '%s\\n' \"it's\" 'a \"test\"'; echo \$((2+3))")
        assertEquals("it's\na \"test\"\n5", r.stdout)
        assertEquals("", sh.run("true").stdout)
        assertEquals("no newline", sh.run("printf 'no newline'").stdout)
        sh.close()
    }

    @Test
    fun commandsDoNotReadTheSessionStdin() = runBlocking {
        val sh = shell()
        val r = sh.run("cat")
        assertEquals(0, r.exitCode)
        assertEquals("after", sh.run("echo after").stdout)
        sh.close()
    }

    @Test
    fun timeoutKillsAndNextCommandRespawns() = runBlocking {
        val sh = shell()
        val r = sh.run("sleep 5", timeoutMs = 300)
        assertEquals(124, r.exitCode)
        assertEquals("ok", sh.run("echo ok").stdout)
        assertEquals(2, sh.spawnCount)
        sh.close()
    }

    @Test
    fun idleSessionIsKilledAndRespawnedLazily() = runBlocking {
        val sh = shell(idleMs = 200)
        sh.run("echo a")
        delay(600)
        sh.run("echo b")
        assertEquals(2, sh.spawnCount)
        sh.close()
    }

    @Test
    fun externallyKilledIdleProcessIsReplacedTransparently() = runBlocking {
        val sh = shell()
        val pid = sh.run("echo \$\$").stdout.trim()
        ProcessBuilder("kill", "-9", pid).start().waitFor()
        delay(200)
        val r = sh.run("echo again")
        assertEquals(0, r.exitCode, r.toString())
        assertEquals("again", r.stdout)
        assertEquals(2, sh.spawnCount)
        sh.close()
    }

    @Test
    fun spawnFailureIsAnErrorResult() = runBlocking {
        val sh = PersistentShell(ShellTier.Root) { throw java.io.IOException("su: not found") }
        val r = sh.run("id")
        assertEquals(-1, r.exitCode)
        assertTrue(r.stderr.contains("su: not found"))
    }

    @Test
    fun framingQuotesSingleQuotes() {
        assertEquals("'a'\\''b'", ShellFraming.quote("a'b"))
        assertEquals(7, ShellFraming.parseMarker("__M__:7", "__M__"))
        assertEquals(null, ShellFraming.parseMarker("x__M__:7", "__M__"))
    }
}
