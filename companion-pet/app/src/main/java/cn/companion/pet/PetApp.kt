package cn.companion.pet

import android.app.Application
import cn.companion.pet.core.memory.MemoryStore
import cn.companion.pet.data.SecretReadResult
import cn.companion.pet.data.SecretStore
import cn.companion.pet.data.Settings
import cn.companion.pet.data.Stats
import cn.companion.pet.net.DeepSeekClient
import cn.companion.pet.work.NightlyWorker
import java.io.File

/** 全局单例的唯一出处。 */
class PetApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var stats: Stats
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var memory: MemoryStore
        private set
    val client by lazy { DeepSeekClient() }

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var keyLoaded = false

    /** API Key 读一次后缓存；设置页保存或清除后调用 [invalidateKey]。 */
    fun apiKey(): String? {
        if (!keyLoaded) {
            cachedKey = (secrets.read() as? SecretReadResult.Available)?.value
            keyLoaded = true
        }
        return cachedKey
    }

    fun invalidateKey() { keyLoaded = false }

    /** 记忆文件的读写都经过这把锁：服务、每晚整理、设置页编辑互斥。 */
    val memoryLock = Any()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        stats = Stats(this)
        secrets = SecretStore(this)
        memory = MemoryStore(File(filesDir, "memory"))
        NightlyWorker.schedule(this)
    }

    companion object {
        lateinit var instance: PetApp
            private set
    }
}
