package com.moling.core.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class FileMindStoreTest {
    private fun tmpFile() = Files.createTempDirectory("mind").resolve("director/mind.json").toFile()

    @Test fun roundTripAndBackup() {
        val f = tmpFile()
        val store = FileMindStore(f)
        assertNull(store.load())
        val a = MindCodec.encode(MindState(currentBatonId = "a"))
        val b = MindCodec.encode(MindState(currentBatonId = "b"))
        store.save(a)
        store.save(b)
        assertEquals("b", MindCodec.decode(store.load()).currentBatonId)
        // 主文件写坏了，退回上一份
        f.writeText("{broken")
        assertEquals("a", MindCodec.decode(store.load()).currentBatonId)
    }
}
