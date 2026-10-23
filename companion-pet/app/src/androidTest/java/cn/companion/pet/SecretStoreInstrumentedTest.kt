package cn.companion.pet

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.companion.pet.data.SecretReadResult
import cn.companion.pet.data.SecretStore
import cn.companion.pet.data.SecretWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 设备上的 Keystore 验证：加密保存、读回、清除；明文字符串不能出现在 SharedPreferences 里。 */
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {

    @Test
    fun keyRoundTripsThroughAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(context)
        assertTrue(store.clear() is SecretWriteResult.Success)
        assertTrue(store.read() is SecretReadResult.Missing)

        val value = "sk-instrumented-" + System.nanoTime()
        assertTrue(store.save(value) is SecretWriteResult.Success)
        val read = store.read()
        assertTrue(read is SecretReadResult.Available)
        assertEquals(value, (read as SecretReadResult.Available).value)

        val prefs = context.getSharedPreferences("secret_store", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString().contains(value) })

        assertTrue(store.clear() is SecretWriteResult.Success)
        assertTrue(store.read() is SecretReadResult.Missing)
    }
}
