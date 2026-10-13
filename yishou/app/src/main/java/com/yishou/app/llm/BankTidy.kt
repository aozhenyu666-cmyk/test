package com.yishou.app.llm

import com.yishou.app.bank.BankPrompt

/**
 * 本机拆不出题的时候（格式太乱、扫描件），让模型把文字整理成题包格式——只整理，不出题、不作答、不用 JSON。
 * 整理完照样在本机拆。长文件按段切开，一段一段整理。
 */
class BankTidy(private val chat: ChatClient) {

    suspend fun tidy(text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): LlmResult<String> {
        val parts = chunks(text)
        val out = StringBuilder()
        parts.forEachIndexed { i, part ->
            onProgress(i + 1, parts.size)
            when (val r = chat.complete(SYSTEM, user(part), "整理题包", json = false)) {
                is LlmResult.Err -> if (out.isEmpty()) return r else return@forEachIndexed
                is LlmResult.Ok -> out.append(r.value.trim()).append("\n---\n")
            }
        }
        return LlmResult.Ok(out.toString())
    }

    private fun user(part: String) = buildString {
        appendLine("下面是从文件里读出来的题目文字（可能有错字、页眉页脚、乱序）。请整理成下面的格式：")
        appendLine("每道题之间单独一行 ---；选择题写【题型】【材料】（没有就省略）【题目】A. B. C. D.【答案】【解析】；申论题写【题型】【材料】【题目】【要点】（分条）。")
        appendLine("只整理，不要改题目和选项的内容，不要自己出新题；原文没有答案就省略【答案】，不要自己作答。文末有答案表的，把答案和解析对回到每道题上。")
        appendLine()
        append(part)
    }

    companion object {
        val SYSTEM = "你负责把题目文字整理成固定的【标签】格式。只输出整理后的题目，不要开场白，不要 JSON，不要代码块。格式参考：\n" +
            BankPrompt.build("言语理解·主旨概括", 1, "真题难度").substringAfter("不要用代码块：").trim()

        /** 按空行切成每段不超过 max 字 */
        fun chunks(text: String, max: Int = 2500): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (para in text.split(Regex("\n\\s*\n"))) {
                if (sb.isNotEmpty() && sb.length + para.length > max) {
                    out += sb.toString()
                    sb.clear()
                }
                if (para.length > max) {
                    para.chunked(max).forEach { out += it }
                } else {
                    sb.append(para).append("\n\n")
                }
            }
            if (sb.isNotBlank()) out += sb.toString()
            return out
        }
    }
}
