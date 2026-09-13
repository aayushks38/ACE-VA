package com.ace.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val launchIntent: Intent?
)

/**
 * Dynamic App Discovery Engine.
 * Resolves target application names to installed package names and launch intents using PackageManager.
 * Eliminates the need for app-specific Kotlin capability classes for third-party apps.
 */
object AppDiscoveryEngine {

    private const val TAG = "ACE_APP_DISCOVERY"
    private var cachedApps: List<InstalledAppInfo>? = null
    private var lastCacheTime: Long = 0L
    private const val CACHE_TTL_MS = 60_000L // 1 minute cache

    fun getInstalledApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            return cachedApps!!
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query launcher activities: ${e.message}")
            emptyList()
        }

        val appsList = mutableListOf<InstalledAppInfo>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkgName = info.activityInfo.packageName
            if (seenPackages.contains(pkgName)) continue
            seenPackages.add(pkgName)

            val label = try {
                info.loadLabel(pm).toString().trim()
            } catch (_: Exception) {
                pkgName
            }

            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            appsList.add(InstalledAppInfo(appName = label, packageName = pkgName, launchIntent = launchIntent))
        }

        cachedApps = appsList
        lastCacheTime = now
        Log.i(TAG, "Discovered ${appsList.size} installed launchable applications.")
        return appsList
    }

    fun findApp(context: Context, targetName: String): InstalledAppInfo? {
        val cleanTarget = targetName.trim().lowercase()
        if (cleanTarget.isBlank()) return null

        val apps = getInstalledApps(context)

        // 1. Exact match (case insensitive)
        apps.firstOrNull { it.appName.lowercase() == cleanTarget }?.let { return it }

        // 2. Exact package match
        apps.firstOrNull { it.packageName.lowercase() == cleanTarget }?.let { return it }

        // 3. Starts with match
        apps.firstOrNull { it.appName.lowercase().startsWith(cleanTarget) }?.let { return it }

        // 4. Contains match
        apps.firstOrNull { it.appName.lowercase().contains(cleanTarget) }?.let { return it }

        // 5. Package name contains target
        apps.firstOrNull { it.packageName.lowercase().contains(cleanTarget) }?.let { return it }

        Log.w(TAG, "No matching installed app found for '$targetName'")
        return null
    }

    fun isAppInstalled(context: Context, targetName: String): Boolean {
        return findApp(context, targetName) != null
    }
}
