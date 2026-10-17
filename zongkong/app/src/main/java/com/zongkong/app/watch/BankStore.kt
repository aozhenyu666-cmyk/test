package com.zongkong.app.watch

import android.content.Context
import android.util.Log
import com.zongkong.core.ZkJson
import java.io.File
import kotlin.random.Random

/**
 * 题库的本机存储：`filesDir/zk/watch/bank.json`。
 *
 * 写失败一律忽略（存储满了、目录建不出来），宁可这一题用内置兜底，也不要让做题页打不开。
 * 导入走 [com.zongkong.app.watch.BankParse]：一行一题，`题干||答案` 或纯题干，UTF-8。
 */
object BankStore {

    private const val TAG = "BankStore"

    fun file(context: Context): File {
        val dir = File(context.filesDir, "zk/watch")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "bank.json")
    }

    fun load(context: Context): Bank = try {
        val f = file(context)
        if (!f.exists()) Bank() else ZkJson.decodeFromString(Bank.serializer(), f.readText())
    } catch (e: Exception) {
        Log.w(TAG, "题库读不了，先用空题库", e)
        Bank()
    }

    fun save(context: Context, bank: Bank) {
        try {
            val f = file(context)
            val text = ZkJson.encodeToString(Bank.serializer(), bank)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "题库写不了，忽略这次保存", e)
        }
    }

    /**
     * 导入一段文本，返回真正新增的条数（和已有题库去重）。
     * 读文件、粘贴都走这里；调用方最好放在 IO 线程。
     */
    fun importText(context: Context, text: String): Int {
        val parsed = BankParse.parse(text)
        if (parsed.isEmpty()) return 0
        val bank = load(context)
        val seen = bank.questions.map { BankParse.normalize(it.stem) }.toMutableSet()
        val add = parsed.filter { seen.add(BankParse.normalize(it.stem)) }
        if (add.isEmpty()) return 0
        save(context, bank.copy(questions = bank.questions + add))
        return add.size
    }

    fun clear(context: Context) = save(context, Bank())

    fun count(context: Context): Int = load(context).questions.size

    /** 前 [n] 条，给设置页预览用。 */
    fun preview(context: Context, n: Int = 5): List<BankQuestion> = load(context).questions.take(n)

    /** 随机抽一道；题库是空的时候返回 null，调用方用内置兜底题。 */
    fun randomQuestion(context: Context, rnd: Random = Random.Default): BankQuestion? {
        val qs = load(context).questions
        return if (qs.isEmpty()) null else qs[rnd.nextInt(qs.size)]
    }
}
