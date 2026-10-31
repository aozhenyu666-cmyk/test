package com.zongkong.app.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.zongkong.core.AiFocus

/** Discover system SAF/photo-picker activities on this phone, including OEM implementations. */
object AiFocusComponents {
    @Suppress("DEPRECATION")
    fun pickers(context: Context): Set<String> {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .flatMap { match ->
                val a = match.activityInfo
                listOf(AiFocus.component(a.packageName, a.name), AiFocus.component(a.packageName, a.targetActivity ?: a.name))
            }.toSet()
        val intents = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            Intent("android.provider.action.PICK_IMAGES").setType("image/*"),
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        )
        val handlers = intents.flatMap { pm.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY) }
        // Some clients first show Android's "choose an app" sheet. This is an Activity too,
        // commonly hosted by android or com.android.intentresolver, not the gallery package.
        val resolvers = intents.flatMap {
            listOfNotNull(
                pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY),
                pm.resolveActivity(Intent.createChooser(it, ""), PackageManager.MATCH_DEFAULT_ONLY),
            )
        }
        return (handlers + resolvers)
            .mapNotNull { match ->
                val a = match.activityInfo
                val cls = a.targetActivity ?: a.name
                val component = AiFocus.component(a.packageName, cls)
                val system = a.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // The declared Intent contract identifies OEM selectors without guessing English class names.
                // Exclude launcher aliases AND their targets: the gallery home must not become a pass.
                component.takeIf { system && component !in launchers && AiFocus.component(a.packageName, a.name) !in launchers }
            }.toSet()
    }
}
