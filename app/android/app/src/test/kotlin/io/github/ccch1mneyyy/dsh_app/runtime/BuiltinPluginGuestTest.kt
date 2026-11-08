package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltinPluginGuestTest {
    private val launch = "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml --host 127.0.0.1 --port 3080 --no-open"

    @Test fun addsTheOverlayRightAfterTheAppsOwnPatch() {
        assertEquals(
            "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml " +
                "--patch /opt/dsh-app/plugins/plugins.patch.yml --host 127.0.0.1 --port 3080 --no-open",
            BuiltinPluginGuest.launchArg(launch),
        )
    }

    @Test fun chainsWithTheOtherOverlaysWithoutDuplicatingItself() {
        val searched = SessionSearchGuest.launchArg(launch)!!
        val once = BuiltinPluginGuest.launchArg(searched)!!
        assertNull(BuiltinPluginGuest.launchArg(once))
        assertTrue(once.contains("--patch /opt/dsh-app/search.patch.yml"))
        assertTrue(once.contains("--patch /opt/dsh-app/plugins/plugins.patch.yml"))
        assertEquals(1, Regex("plugins\\.patch\\.yml").findAll(once).count())
    }

    @Test fun leavesOtherArgumentsAndSafeModeAlone() {
        assertNull(BuiltinPluginGuest.launchArg("/usr/bin/env"))
        assertNull(BuiltinPluginGuest.launchArg("exec /usr/local/bin/dsh web --profile safe --port 1"))
    }

    @Test fun overlayInsertsEveryBundledPluginByAbsolutePath() {
        val yaml = BuiltinPluginGuest.PATCH_YAML
        assertTrue(yaml.contains("- id: dsh-go-relationship\n"))
        assertTrue(yaml.contains("      name: /opt/dsh-app/plugins/dsh-go-relationship/lib/index.js\n"))
        // Two schedulers racing for the same voice: the old speak-up timer is not deployed.
        assertTrue(!yaml.contains("- id: dsh-speak-up\n"))
        assertTrue(yaml.contains("- id: dsh-feiyu-mood\n"))
        assertTrue(yaml.contains("      name: /opt/dsh-app/plugins/dsh-feiyu-mood/lib/index.js\n"))
        assertTrue(yaml.contains("- id: dsh-fish-strict\n"))
        assertTrue(yaml.contains("      name: /opt/dsh-app/plugins/dsh-fish-strict/lib/index.js\n"))
        assertTrue(yaml.contains("- id: dsh-go-memory\n"))
        assertTrue(yaml.contains("      name: /opt/dsh-app/plugins/dsh-go-memory/lib/index.js\n"))
        assertTrue(yaml.contains("- id: dsh-go-companion\n"))
        assertTrue(yaml.contains("      name: /opt/dsh-app/plugins/dsh-go-companion/lib/index.js\n"))
        // package.json is what makes the copied .js files ESM on the guest.
        assertTrue(BuiltinPluginGuest.PLUGINS.all { it.files.contains("package.json") })
        assertEquals(5, BuiltinPluginGuest.PLUGINS.size)
    }

    /**
     * A missing entry is invisible until the guest throws MODULE_NOT_FOUND at engine start,
     * so every .js that actually ships has to be in the list.
     */
    @Test fun everyShippedScriptIsListed() {
        val assets = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/assets/engine/plugins") }
            .firstOrNull { it.isDirectory }
            ?: return
        for (plugin in BuiltinPluginGuest.PLUGINS) {
            val lib = File(File(assets, plugin.id), "lib")
            val onDisk = lib.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }
                .map { it.relativeTo(File(assets, plugin.id)).invariantSeparatorsPath }
                .toSet()
            assertEquals("${plugin.id} ships every script", emptySet<String>(), onDisk - plugin.files.toSet())
        }
    }
}
