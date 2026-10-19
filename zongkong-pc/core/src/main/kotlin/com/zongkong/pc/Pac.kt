package com.zongkong.pc

/**
 * 网络层拦网站：系统代理设成一个本机的 PAC 脚本，要拦的域名交给本机的“拦截口”（它只回一句“关着”），
 * 其他一律直连。总控没在运行时 PAC 地址打不开，浏览器自动直连——不会把网弄断。
 */
object Pac {
    /** [fallback]：不拦的走哪（原来开着代理软件的，照旧走它）。 */
    fun script(blocked: Set<String>, proxyPort: Int, fallback: String = "DIRECT"): String = buildString {
        append("function FindProxyForURL(url, host) {\n")
        append("  host = host.toLowerCase();\n")
        append("  if (isPlainHostName(host) || host == \"localhost\" || shExpMatch(host, \"127.*\")) return \"DIRECT\";\n")
        append("  var b = [")
        append(blocked.sorted().joinToString(",") { "\"" + it.lowercase().replace("\"", "") + "\"" })
        append("];\n")
        append("  for (var i = 0; i < b.length; i++) {\n")
        append("    if (host == b[i] || dnsDomainIs(host, \".\" + b[i])) return \"PROXY 127.0.0.1:$proxyPort\";\n")
        append("  }\n")
        append("  return \"" + fallback.replace("\"", "") + "\";\n")
        append("}\n")
    }

    /** 和 PAC 里一样的判断（测试和拦截口用）。 */
    fun matches(host: String, blocked: Set<String>): Boolean {
        val h = host.lowercase().substringBefore(':')
        return blocked.any { h == it || h.endsWith(".$it") }
    }

    /** 拦截口回给浏览器的页面（http 的网站能看到；https 的浏览器只显示“连接失败”）。 */
    fun page(host: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>总控：关着</title>
        <style>body{font-family:system-ui,"Microsoft YaHei";background:#F5F0E8;color:#2A241F;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
        div{max-width:520px;padding:32px}h1{font-weight:600}p{line-height:1.7;color:#6B6157}</style></head>
        <body><div><h1>$host 现在关着</h1><p>这是总控在拦。额度用完、锁机或者没说这次开电脑做什么时，娱乐网站在网络这一层就打不开。<br>真要用：打开总控，点“申请”，说清楚做什么、多久。</p></div></body></html>
    """.trimIndent()
}

/**
 * 总控（AI）要在电脑上执行的命令，先分级：
 *  只读（看进程、看文件列表、看网络……）直接执行；
 *  会改东西的，弹给你点“执行 / 不执行”；
 *  破坏性的（格式化、删一大片、改系统启动、关掉安全软件……）一律不执行。
 */
object ShellSafety {
    enum class Level { READ, CONFIRM, FORBIDDEN }

    private val forbidden = listOf(
        Regex("""\bformat(-volume)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(diskpart|bcdedit|cipher\s+/w|vssadmin|wbadmin|fsutil|takeown|icacls)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(rd|rmdir)\s+/s""", RegexOption.IGNORE_CASE),
        Regex("""\bdel\s+.*(/s|/q).*""", RegexOption.IGNORE_CASE),
        Regex("""remove-item\b.*-recurse""", RegexOption.IGNORE_CASE),
        Regex("""\brm\s+-(r|rf|fr)\b""", RegexOption.IGNORE_CASE),
        Regex("""\breg(\.exe)?\s+delete\s+hklm""", RegexOption.IGNORE_CASE),
        Regex("""set-mppreference|disable-windowsoptionalfeature|uninstall-windowsfeature""", RegexOption.IGNORE_CASE),
        Regex("""invoke-expression|\biex\b|downloadstring|invoke-webrequest.*\|\s*iex""", RegexOption.IGNORE_CASE),
        Regex("""-encodedcommand|\s-enc\s""", RegexOption.IGNORE_CASE),
        Regex("""\bnet\s+user\b.*(/add|/delete)""", RegexOption.IGNORE_CASE),
        Regex("""clear-recyclebin|clear-eventlog|wevtutil\s+cl""", RegexOption.IGNORE_CASE),
        // 不许借命令把总控自己关掉、删掉
        Regex("""zongkong""", RegexOption.IGNORE_CASE),
    )

    /** 只读命令的开头（PowerShell 和 cmd）。整条命令里每一段（; | && 分开）都是只读才算只读。 */
    private val readOnly = listOf(
        "get-", "tasklist", "dir", "ls", "type", "cat", "whoami", "hostname", "ipconfig", "ping", "nslookup", "systeminfo",
        "echo", "where", "test-path", "resolve-dnsname", "test-netconnection", "netstat", "query user", "quser", "ver",
        "select-object", "sort-object", "where-object", "format-table", "format-list", "measure-object", "out-string",
        "select-string", "findstr", "convertto-json", "group-object", "foreach-object", "%", "?", "sort", "more", "date", "time /t",
    )

    fun level(command: String): Level {
        val c = command.trim()
        if (c.isEmpty()) return Level.FORBIDDEN
        if (forbidden.any { it.containsMatchIn(c) }) return Level.FORBIDDEN
        // 输出重定向到文件也算改东西
        if (Regex("""(^|[^2])>\s*[^&]""").containsMatchIn(c) || c.contains("out-file", ignoreCase = true) || c.contains("set-content", ignoreCase = true)) return Level.CONFIRM
        val parts = c.split(Regex("""\s*(;|\|\||&&|\|)\s*""")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val ro = parts.all { p -> readOnly.any { r -> p == r || p.startsWith("$r ") || (r.endsWith("-") && p.startsWith(r)) } }
        return if (ro) Level.READ else Level.CONFIRM
    }
}
