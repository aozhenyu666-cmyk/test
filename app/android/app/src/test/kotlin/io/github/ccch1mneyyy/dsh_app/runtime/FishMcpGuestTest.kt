package io.github.ccch1mneyyy.dsh_app.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishMcpGuestTest {
    private val launch = "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml --host 127.0.0.1 --port 3080 --no-open"

    @Test fun addsTheOverlayRightAfterTheAppsOwnPatch() {
        assertEquals(
            "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --patch /etc/dsh-app/dsh-web.patch.yml " +
                "--patch /opt/dsh-app/fish/fish.patch.yml --host 127.0.0.1 --port 3080 --no-open",
            FishMcpGuest.launchArg(launch),
        )
        assertNull(FishMcpGuest.launchArg("/usr/bin/env"))
        assertNull(FishMcpGuest.launchArg("exec /usr/local/bin/dsh web --profile safe --port 1"))
    }

    @Test fun writesAStreamableHttpMcpEntryAndNeverLeaksTheTokenIntoOtherFields() {
        val root = Files.createTempDirectory("fish-mcp").toFile()
        try {
            val config = FishMcpGuest.configFile(root)
            config.parentFile.mkdirs()
            config.writeText("""{"url":"http://100.64.0.8:8788/mcp","token":"abcdefghijklmnop123456","serverName":"fish"}""")
            assertTrue(FishMcpGuest.writeFiles(root))
            val yaml = FishMcpGuest.file(root).readText()
            assertTrue(yaml.contains("transport: streamable-http"))
            assertTrue(yaml.contains("url: 'http://100.64.0.8:8788/mcp'"))
            assertTrue(yaml.contains("Authorization: 'Bearer abcdefghijklmnop123456'"))
            assertTrue(yaml.contains("serverName: fish"))
            assertTrue(yaml.contains("failOnStartupError: false"))

            config.writeText("""{"url":"ftp://x","token":"abcdefghijklmnop123456"}""")
            assertFalse(FishMcpGuest.writeFiles(root))
            assertFalse(FishMcpGuest.file(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsShortTokensAndMissingFiles() {
        val root = Files.createTempDirectory("fish-mcp").toFile()
        try {
            assertNull(FishMcpGuest.readEndpoint(FishMcpGuest.configFile(root)))
            val config = FishMcpGuest.configFile(root)
            config.parentFile.mkdirs()
            config.writeText("""{"url":"https://pc.example/mcp","token":"short"}""")
            assertNull(FishMcpGuest.readEndpoint(config))
            assertFalse(FishMcpGuest.writeFiles(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
