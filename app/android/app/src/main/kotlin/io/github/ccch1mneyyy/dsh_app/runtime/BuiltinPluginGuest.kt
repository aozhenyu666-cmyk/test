package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Prebuilt DSH plugins that ship inside the APK.
 *
 * A phone-side model is bad at writing code, so a product feature is packaged
 * here on the build machine and deployed as data: at every engine start the
 * files are copied from the Flutter asset bundle into the writable part of the
 * rootfs, and one extra `--patch` overlay inserts them by absolute path. No
 * pnpm, no registry, no model-authored source on the phone.
 *
 * The overlay is written only when every file landed; a missing plugin would
 * otherwise make DSH fail to start. Safe mode never gets it, like the other
 * app overlays.
 */
object BuiltinPluginGuest {
    const val GUEST_DIR = "/opt/dsh-app/plugins"
    const val PATCH = "$GUEST_DIR/plugins.patch.yml"

    /** Where Flutter packs `app/assets/engine/plugins/` inside the APK. */
    private const val ASSET_ROOT = "flutter_assets/assets/engine/plugins"

    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    /** One bundled plugin: asset folder, loadable entry, and every file to copy. */
    data class Plugin(
        val id: String,
        val entry: String,
        val files: List<String>,
    )

    /**
     * The shipped set. Adding a feature means: drop its source under
     * `app/assets/engine/plugins/<id>/`, list its files here (Flutter does not
     * recurse into asset subdirectories by itself), and rebuild the APK.
     */
    val PLUGINS = listOf(
        Plugin(
            id = "dsh-go-relationship",
            entry = "lib/index.js",
            files = listOf(
                "package.json",
                "cordis.patch.yml",
                "lib/index.js",
                "lib/hostdeps.mjs",
                "lib/core/config.js",
                "lib/core/db.js",
                "lib/core/engine.js",
                "lib/core/intervention.js",
                "lib/core/memory-writer.js",
                "lib/core/text.js",
                "lib/core/threads.js",
                "lib/core/time.js",
            ),
        ),
        // Phone is the only proactive scheduler (dsh-go-companion). The old dsh-speak-up
        // timer is deliberately not deployed: two schedulers would race for the same voice.
        // Emotion state for phone conversations; host half only.
        Plugin(
            id = "dsh-feiyu-mood",
            entry = "lib/index.js",
            files = listOf(
                "package.json",
                "cordis.patch.yml",
                "LICENSE",
                "lib/index.js",
                "lib/selfupdate.mjs",
            ),
        ),
        // dsh-fish-strict (strict check-ins) and dsh-speak-up (dice-driven openers) are retired
        // (P0, 2026-11-08): dsh-go-companion does both now, and running them next to it meant two
        // voices / two state machines. Their sources stay under assets for reference only.
        // Phone-local memory authority: SQLite (JSON fallback) under DSH_HOME.
        // Also contributes the native memory panel and a composer action.
        Plugin(
            id = "dsh-go-memory",
            entry = "lib/index.js",
            files = listOf(
                "package.json",
                "cordis.patch.yml",
                "lib/index.js",
                "lib/model.js",
                "lib/store.js",
                "lib/migrate.js",
                "lib/view.js",
            ),
        ),
        // One proactive decision center: topic pool from fish-memory, cold-session turns,
        // and a requestId ledger. The app's alarms only wake it.
        Plugin(
            id = "dsh-go-companion",
            entry = "lib/index.js",
            files = listOf(
                "package.json",
                "cordis.patch.yml",
                "lib/index.js",
                "lib/core.js",
                "lib/topics.js",
                "lib/garden.js",
                "lib/context.js",
                "lib/prompt.js",
                "lib/ledger.js",
                "lib/plan.js",
                "lib/tz.js",
                "lib/classify.js",
                "lib/rally.js",
                "lib/serve.js",
                "lib/stock.js",
                "lib/judge.js",
                "lib/silence.js",
                "lib/persona.js",
                "lib/phone.js",
                "lib/tide.js",
                "lib/temper.js",
                "lib/drills.js",
                "lib/tracks.js",
                "lib/muse.js",
                "lib/workflows.js",
                "lib/brain.js",
                "lib/predict.js",
                "lib/vibe.js",
                "lib/samples.js",
                "lib/force.js",
                "lib/roles.js",
                "lib/gear.js",
                "lib/gemini.js",
                "lib/trust.js",
                "lib/panel.js",
                "lib/notes.js",
                "lib/rhythm.js",
                "lib/wall.js",
                "lib/wall_auditor.js",
                "lib/vault_wall.js",
                "lib/sisters.js",
                "lib/deposit.js",
                "lib/daily.js",
                "lib/followup.js",
                "lib/machine.js",
                "lib/budget.js",
                "lib/stance.js",
                "lib/wall_voice.js",
                "lib/mount.js",
                // R7 B4：情绪池。状态层那一行和 /request 里的 burst 计划都来自它。
                "lib/emotion.js",
                // R7 B2：夜间整理。产物是 links/gaps/carry（素材），不是消息。
                "lib/nightly.js",
                // R8 J1：局的数据。八张表加在 fish-memory.sqlite 里，写入只走 bouts.js；
                // rebuild.js 是「以账本为准」的重建脚本（node lib/rebuild.js --home <dir>）。
                "lib/bouts.js",
                "lib/rebuild.js",
                // R8 J2/J3：能力库（可校验的具名积木）+ 协议格式与解释器。
                "lib/capabilities.js",
                "lib/protocol.js",
                // R8 J4/J9：五个内置协议（JSON 也是部署物，漏了真机 readdirSync 直接空目录）、
                // 协议加载器、局官（一拍一拍推进 + 沟通上限 + 混乱处理）。
                "lib/builtin.js",
                "lib/boutrun.js",
                // R8 W1：局的面板（四页）与它的数据。视图只认 data.* 绑定，措辞来自协议与局官。
                "lib/bouts_view.js",
                "lib/bouts_panel.js",
                "lib/protocols/execute.json",
                "lib/protocols/study.json",
                "lib/protocols/outing.json",
                "lib/protocols/design.json",
                "lib/protocols/rest.json",
                // R8 W4：设定（作息模板 / 最少局数 / 局型预算 / 升级阈值）。改紧立即生效，
                // 改松走 24 小时冷却期——P1 排局、P4 升级都从这里读阈值。
                "lib/settings.js",
                // R8 P1：计划模态。候选与规则全在代码里，模型只负责把计划说出来。
                "lib/dayplan.js",
                // R8 P2：全天主动。计划生效后他不再开局，局来开他——换局有额度，「不开」不许说。
                "lib/autobout.js",
                // R8 P3：答错只重打、敷衍走升级、混乱只压缩。度量复用 J3 那一套。
                "lib/reply.js",
                // R8 P4：升级阶梯。E1/E1′/E2/E3 与 L1/L2/L3 全在代码里，模型无权减免。
                "lib/escalate.js",
                // R8 P1/P2 接线：计划的节拍。引擎停过之后过点的局记空局、不补开。
                "lib/planwire.js",
            ),
        ),
    )

    val PATCH_YAML: String = buildString {
        appendLine("# dsh-app: prebuilt plugins shipped inside the APK. The DSH App rewrites this")
        appendLine("# directory at every engine start and passes this file with --patch on normal")
        appendLine("# launches (never in safe mode). Edits do not survive.")
        appendLine("- insert:")
        for (plugin in PLUGINS) {
            appendLine("    - id: ${plugin.id}")
            appendLine("      name: $GUEST_DIR/${plugin.id}/${plugin.entry}")
        }
    }

    fun file(rootfs: File) = File(rootfs, PATCH.removePrefix("/"))

    /** The launch command with the overlay right after the app's own one; null when [arg] is not the `dsh web` launch. */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        if (arg.contains("--patch $PATCH")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    /**
     * Copies every bundled plugin, then writes the overlay. Files already up to
     * date are left alone. False (and no overlay file) when anything is
     * missing, so the launch leaves the whole set out instead of half-loading.
     */
    fun writeFiles(context: Context, rootfs: File): Boolean {
        val patch = file(rootfs)
        return try {
            val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
            if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            for (plugin in PLUGINS) {
                for (name in plugin.files) {
                    val bytes = context.assets.open("$ASSET_ROOT/${plugin.id}/$name").use { it.readBytes() }
                    val target = File(dir, "${plugin.id}/$name")
                    if (!target.parentFile.isDirectory && !target.parentFile.mkdirs() && !target.parentFile.isDirectory) {
                        throw IOException("could not create ${target.parentFile.path}")
                    }
                    writeIfChanged(target, bytes)
                }
            }
            writeIfChanged(patch, PATCH_YAML.toByteArray())
            true
        } catch (_: Exception) {
            patch.delete()
            false
        }
    }

    /** Sibling temp file renamed over [target]; only a regular file is compared (never a link or FIFO). */
    private fun writeIfChanged(target: File, bytes: ByteArray) {
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.readBytes().contentEquals(bytes)) return
        val tmp = File(target.parentFile, ".${target.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
