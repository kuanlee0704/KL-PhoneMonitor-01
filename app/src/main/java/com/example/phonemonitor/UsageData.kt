package com.example.phonemonitor

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.TimeZone

object UsageAccess {
    fun granted(ctx: Context): Boolean = try {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT)
            ctx.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    fun open(ctx: Context) {
        try { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) {}
    }
}

object Apps {
    private val labels = ConcurrentHashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try { ctx.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }

    fun launchable(ctx: Context, pkg: String): Boolean = try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (e: Exception) { false }

    fun openInfo(ctx: Context, pkg: String) {
        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (e: Exception) {}
    }

    fun uninstall(ctx: Context, pkg: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (e: Exception) {}
    }
}

class AppStat(val pkg: String) {
    var fgMs = 0L
    var launches = 0
    var last = 0L
}

class UsageResult(
    val apps: List<AppStat>, val screenOnMs: Long, val unlocks: Int,
    val hourMs: LongArray,   // 一天 24 小時各時段螢幕使用(累計)
    val dayMs: LongArray     // 最近 7 天(含今天)每日螢幕時間
)

class FgsStat(val pkg: String, val fgMs: Long, val fgsMs: Long, val last: Long)

object AppUsage {
    const val DAY = 86_400_000L

    fun dayStart(t: Long): Long {
        val tz = TimeZone.getDefault().getOffset(t)
        return ((t + tz) / DAY) * DAY - tz
    }

    /** 由系統事件計算螢幕時間與各 App 前景時間 */
    fun compute(ctx: Context, from: Long, to: Long): UsageResult {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val stats = HashMap<String, AppStat>()
        val start = HashMap<String, Long>()
        val active = HashMap<String, Int>()
        val seen = HashSet<String>()
        var scrStart = -1L; var sawScr = false; var screenMs = 0L; var unlocks = 0
        val hour = LongArray(24); val day = LongArray(7)
        val tz = TimeZone.getDefault().getOffset(to)
        val dayOrigin = dayStart(to) - 6 * DAY

        fun stat(p: String) = stats.getOrPut(p) { AppStat(p) }

        fun addScreen(a: Long, b: Long) {
            if (b <= a) return
            screenMs += b - a
            var t = a
            while (t < b) {
                val hs = ((t + tz) / 3_600_000L) * 3_600_000L - tz
                val seg = minOf(b, hs + 3_600_000L) - t
                if (seg <= 0) break
                hour[(((t + tz) / 3_600_000L) % 24).toInt()] += seg
                if (t >= dayOrigin) { val di = ((t - dayOrigin) / DAY).toInt(); if (di <= 6) day[di] += seg }
                t += seg
            }
        }

        fun closeAll(t: Long) {
            for ((k, s) in start) stat(k.substringBefore("/")).fgMs += (t - s).coerceAtLeast(0L)
            start.clear(); active.clear()
        }

        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val t = e.timeStamp; val type = e.eventType
            val pkg = e.packageName ?: ""
            when (type) {
                1 -> if (pkg.isNotEmpty()) {          // ACTIVITY_RESUMED
                    val key = pkg + "/" + (e.className ?: "")
                    if (!start.containsKey(key)) start[key] = t
                    val c = active.getOrDefault(pkg, 0)
                    if (c == 0) stat(pkg).launches++
                    active[pkg] = c + 1
                    seen.add(key)
                }
                2 -> if (pkg.isNotEmpty()) {          // ACTIVITY_PAUSED
                    val key = pkg + "/" + (e.className ?: "")
                    val s = start.remove(key) ?: (if (!seen.contains(key)) from else null)
                    if (s != null) stat(pkg).fgMs += (t - s).coerceAtLeast(0L)
                    stat(pkg).last = t
                    active[pkg] = maxOf(0, active.getOrDefault(pkg, 0) - 1)
                    seen.add(key)
                }
                15 -> if (scrStart < 0) scrStart = t   // SCREEN_INTERACTIVE
                16 -> {                                // SCREEN_NON_INTERACTIVE
                    closeAll(t)
                    val s = if (scrStart >= 0) scrStart else if (!sawScr) from else -1L
                    if (s >= 0) addScreen(s, t)
                    scrStart = -1L; sawScr = true
                }
                18 -> unlocks++                        // KEYGUARD_HIDDEN
            }
        }
        if (scrStart >= 0 || !sawScr) closeAll(to) else start.clear()
        if (scrStart >= 0) addScreen(scrStart, to)

        val apps = stats.values.filter { it.fgMs >= 60_000L }.sortedByDescending { it.fgMs }
        return UsageResult(apps, screenMs, unlocks, hour, day)
    }

    /** 目前在前景的 App(根據最近 30 分鐘事件推測) */
    fun foregroundPkg(ctx: Context): String? {
        if (!UsageAccess.granted(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 30 * 60_000L, now)
            val e = UsageEvents.Event()
            var cur: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    1 -> cur = e.packageName
                    2 -> if (e.packageName == cur) cur = null
                    16 -> cur = null
                }
            }
            cur
        } catch (ex: Throwable) { null }
    }

    /** 各 App 的前景時間 / 前景服務(背景常駐)時間 */
    fun aggregate(ctx: Context, from: Long, to: Long): List<FgsStat> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return usm.queryAndAggregateUsageStats(from, to).values.map { u ->
            val fgs = try {
                (u.javaClass.getMethod("getTotalTimeForegroundServiceUsed").invoke(u) as Long)
            } catch (ex: Throwable) { 0L }
            FgsStat(u.packageName, u.totalTimeInForeground, fgs, u.lastTimeUsed)
        }
    }

    fun lastUsedMap(ctx: Context, days: Int): Map<String, Long> = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryAndAggregateUsageStats(now - days * DAY, now).mapValues { it.value.lastTimeUsed }
    } catch (e: Throwable) { emptyMap() }
}

object NetUsage {
    const val MOBILE = 0   // ConnectivityManager.TYPE_MOBILE
    const val WIFI = 1     // ConnectivityManager.TYPE_WIFI

    class App(val uid: Int, val fg: Long, val bg: Long) { val total: Long get() = fg + bg }

    private fun nsm(ctx: Context) = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    /** [下載, 上傳] */
    fun total(ctx: Context, type: Int, from: Long, to: Long): LongArray {
        val b = nsm(ctx).querySummaryForDevice(type, null, from, to)
        return longArrayOf(b.rxBytes, b.txBytes)
    }

    fun perApp(ctx: Context, type: Int, from: Long, to: Long): List<App> {
        val map = HashMap<Int, LongArray>()
        val st = nsm(ctx).querySummary(type, null, from, to)
        val b = NetworkStats.Bucket()
        try {
            while (st.hasNextBucket()) {
                st.getNextBucket(b)
                val arr = map.getOrPut(b.uid) { LongArray(2) }
                val bytes = b.rxBytes + b.txBytes
                if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) arr[0] += bytes else arr[1] += bytes
            }
        } finally { st.close() }
        return map.map { App(it.key, it.value[0], it.value[1]) }.filter { it.total > 0 }.sortedByDescending { it.total }
    }

    /** 回傳 (顯示名稱, 代表套件名) */
    fun uidName(ctx: Context, uid: Int): Pair<String, String?> {
        when (uid) {
            0 -> return Pair("Android 系統核心", null)
            1000 -> return Pair("Android 系統", null)
            -4 -> return Pair("已解除安裝的 App", null)
            -5 -> return Pair("網路共享(熱點)", null)
        }
        val pkgs = try { ctx.packageManager.getPackagesForUid(uid) } catch (e: Exception) { null }
        if (pkgs == null || pkgs.isEmpty()) return Pair("UID $uid", null)
        val main = Apps.label(ctx, pkgs[0])
        return Pair(if (pkgs.size > 1) "$main 等 ${pkgs.size} 個" else main, pkgs[0])
    }

    /** 某個 UID 最近 days 天,每天的 (行動, Wi-Fi) 位元組 */
    fun uidDaily(ctx: Context, uid: Int, days: Int): List<Triple<Long, Long, Long>> {
        val now = System.currentTimeMillis(); val from = AppUsage.dayStart(now) - (days - 1) * AppUsage.DAY
        val res = LongArray(days * 2)
        for ((ti, type) in listOf(MOBILE, WIFI).withIndex()) try {
            val st = nsm(ctx).queryDetailsForUid(type, null, from, now, uid)
            val b = NetworkStats.Bucket()
            try {
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    val idx = if (b.startTimeStamp < from) 0 else ((b.startTimeStamp - from) / AppUsage.DAY).toInt()
                    if (idx in 0 until days) res[idx * 2 + ti] += b.rxBytes + b.txBytes
                }
            } finally { st.close() }
        } catch (e: Exception) {}
        return (0 until days).map { Triple(from + it * AppUsage.DAY, res[it * 2], res[it * 2 + 1]) }
    }

    fun cycleStart(now: Long, day: Int): Long {
        val c = Calendar.getInstance(); c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        if (c.timeInMillis > now) {
            c.add(Calendar.MONTH, -1)
            c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return c.timeInMillis
    }
}


/** 把每天的 App 使用時間封存到資料庫,突破系統只保留約 7 天的限制 */
object UsageArchive {
    private var lastSync = 0L

    fun sync(ctx: Context, force: Boolean = false) {
        if (!UsageAccess.granted(ctx)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 10 * 60_000L) return
        lastSync = now
        val db = Db.get(ctx); val today = AppUsage.dayStart(now)
        for (i in 0..9) {
            val d = today - i * AppUsage.DAY
            val has = db.hasUsageDay(d)
            if (has && i > 1) continue               // 舊的日子已封存就不再覆蓋(系統事件可能已被清掉)
            val r = try { AppUsage.compute(ctx, d, minOf(d + AppUsage.DAY, now)) } catch (e: Throwable) { continue }
            if (r.apps.isEmpty() && r.screenOnMs == 0L && has) continue
            if (r.apps.isEmpty() && r.screenOnMs == 0L && i > 1) continue
            db.saveUsageDay(d, r.apps, r.screenOnMs, r.unlocks)
        }
    }
}
