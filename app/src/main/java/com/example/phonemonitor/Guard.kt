package com.example.phonemonitor

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Block(val reason: String, val canSnooze: Boolean)

/** 短影音區塊的偵測規則(App 更新後元件 ID 可能改變,偵測會同時看「被選取的分頁」與「畫面元件 ID」) */
class Feature(
    val key: String, val name: String, val pkgs: Set<String>,
    val idKw: List<String>, val tabKw: List<String>, val homeKw: List<String>
)

object Features {
    val all = listOf(
        Feature("ig", "Instagram Reels", setOf("com.instagram.android"),
            listOf("clips_viewer", "reels_viewer"), listOf("reels", "連續短片", "短影音"), listOf("home", "首頁", "主頁")),
        Feature("yt", "YouTube Shorts", setOf("com.google.android.youtube"),
            listOf("reel_recycler", "reel_player_page", "shorts_"), listOf("shorts"), listOf("home", "首頁")),
        Feature("fb", "Facebook Reels", setOf("com.facebook.katana", "com.facebook.lite"),
            listOf("reels_viewer", "reel_viewer"), listOf("reels", "連續短片"), listOf("home", "首頁"))
    )
    val byPkg: Map<String, Feature> = all.flatMap { f -> f.pkgs.map { it to f } }.toMap()
    val TIKTOK = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
}

object Guard {
    private val snoozes = ConcurrentHashMap<String, Long>()
    private val usageCache = HashMap<String, Pair<Long, Long>>()
    @Volatile var lastDetect = "尚無偵測紀錄"
    @Volatile var lastDump = ""

    fun snooze(pkg: String, ms: Long) { snoozes[pkg] = System.currentTimeMillis() + ms }
    fun inFocus(ctx: Context) = Prefs.focusUntil(ctx) > System.currentTimeMillis()

    fun inSchedule(ctx: Context): Boolean {
        if (!Prefs.schedOn(ctx)) return false
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val s = Prefs.schedStart(ctx); val e = Prefs.schedEnd(ctx)
        return if (s <= e) m in s until e else (m >= s || m < e)
    }

    private fun usedMinutes(ctx: Context, pkg: String): Long {
        val now = System.currentTimeMillis()
        usageCache[pkg]?.let { if (now - it.first < 20_000L) return it.second }
        val used = try {
            (AppUsage.compute(ctx, AppUsage.dayStart(now), now).apps.firstOrNull { it.pkg == pkg }?.fgMs ?: 0L) / 60_000L
        } catch (e: Throwable) { 0L }
        usageCache[pkg] = Pair(now, used)
        return used
    }

    /** 該 App 此刻是否應被封鎖;回傳 null 代表放行 */
    fun reason(ctx: Context, pkg: String): Block? {
        if ((snoozes[pkg] ?: 0L) > System.currentTimeMillis()) return null
        if (pkg in Prefs.blocked(ctx)) return Block("你已將這個 App 設為永久封鎖", false)
        if (pkg in Prefs.focusApps(ctx)) {
            if (inFocus(ctx)) return Block("專注模式進行中", true)
            if (inSchedule(ctx)) return Block("目前是排程專注時段", true)
        }
        val lim = Prefs.limits(ctx)[pkg] ?: 0
        if (lim > 0 && Prefs.limitBlock(ctx) && UsageAccess.granted(ctx)) {
            val used = usedMinutes(ctx, pkg)
            if (used >= lim) return Block("今日已使用 $used 分鐘,超過上限 $lim 分鐘", true)
        }
        return null
    }

    /** 退出 App:先回桌面,再結束其背景程序(Android 不允許一般 App 直接「強制停止」別的 App) */
    fun exit(ctx: Context, pkg: String) {
        val svc = GuardService.instance
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (svc != null) {
            svc.goHome()
            Handler(Looper.getMainLooper()).postDelayed({ try { am.killBackgroundProcesses(pkg) } catch (_: Throwable) {} }, 900)
            Toast.makeText(ctx, "已退出 ${Apps.label(ctx, pkg)}", Toast.LENGTH_SHORT).show()
        } else {
            try { am.killBackgroundProcesses(pkg) } catch (_: Throwable) {}
            Toast.makeText(ctx, "已結束背景程序。若它仍在前景,請先開啟「專注防護」服務,或到 App 資訊按「強制停止」。", Toast.LENGTH_LONG).show()
        }
    }
}

class GuardService : AccessibilityService() {
    companion object {
        @Volatile var instance: GuardService? = null
        fun enabled(ctx: Context): Boolean {
            if (instance != null) return true
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return s.contains("${ctx.packageName}/${GuardService::class.java.name}") || s.contains("${ctx.packageName}/.GuardService")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastBlock = 0L
    private var lastCheck = 0L
    private var lastScan = 0L
    private var lastExit = 0L
    private var activeFeat: Feature? = null

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; handler.removeCallbacksAndMessages(null); super.onDestroy() }

    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val type = e.eventType
        val now = SystemClock.uptimeMillis()

        // 1) 封鎖 / 專注 / 上限
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastCheck > 1500) {
            lastCheck = now
            Guard.reason(this, pkg)?.let { block(pkg, it); return }
        }

        // 2) 短影音區塊
        val f = Features.byPkg[pkg] ?: return
        if (Prefs.feature(this, f.key) < 0) return
        if (activeFeat != null || now - lastScan < 500) return
        lastScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val hit = scan(root, f) ?: return
        onFeatureDetected(f, hit)
    }

    private fun block(pkg: String, b: Block) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 1500) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            try {
                startActivity(Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("pkg", pkg).putExtra("reason", b.reason).putExtra("snooze", b.canSnooze))
            } catch (_: Exception) {}
        }, 300)
    }

    // ---------- 短影音 ----------
    private fun scan(root: AccessibilityNodeInfo, f: Feature): String? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        var tabHit: String? = null
        while (q.isNotEmpty() && n < 900) {
            val node = q.removeFirst(); n++
            val id = node.viewIdResourceName
            if (id != null && f.idKw.any { id.contains(it, true) }) return "元件ID:" + id.substringAfter(":id/")
            if (node.isSelected) {
                val d = (node.contentDescription ?: node.text)?.toString()
                if (d != null && d.length < 40 && f.tabKw.any { d.contains(it, true) }) tabHit = "選取分頁:$d"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return tabHit
    }

    private fun stillOn(f: Feature): Boolean {
        val root = rootInActiveWindow ?: return false
        val p = root.packageName?.toString() ?: return false
        return p in f.pkgs && scan(root, f) != null
    }

    private fun onFeatureDetected(f: Feature, hit: String) {
        Guard.lastDetect = "${f.name} · $hit · " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val mode = Prefs.feature(this, f.key)
        if (mode == 0 || (mode > 0 && Prefs.featUsedMs(this, f.key) >= mode * 60_000L)) { exitFeature(f); return }
        // 額度模式:每 2 秒累計一次
        activeFeat = f
        handler.postDelayed(tick, 2000)
    }

    private val tick = object : Runnable {
        override fun run() {
            val f = activeFeat ?: return
            if (!stillOn(f)) { activeFeat = null; return }
            val mode = Prefs.feature(this@GuardService, f.key)
            if (mode > 0) Prefs.addFeatUsed(this@GuardService, f.key, 2000L)
            if (mode == 0 || (mode > 0 && Prefs.featUsedMs(this@GuardService, f.key) >= mode * 60_000L)) {
                activeFeat = null; exitFeature(f)
            } else handler.postDelayed(this, 2000)
        }
    }

    private fun exitFeature(f: Feature) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExit < 1200) return
        lastExit = now
        Prefs.incFeatBlocks(this, f.key)
        Toast.makeText(this, "${f.name} 已封鎖", Toast.LENGTH_SHORT).show()
        val root = rootInActiveWindow
        val home = root?.let { findHome(it, f) }
        if (home != null) {
            var n: AccessibilityNodeInfo? = home
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun findHome(root: AccessibilityNodeInfo, f: Feature): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root); var n = 0
        while (q.isNotEmpty() && n < 900) {
            val node = q.removeFirst(); n++
            val d = (node.contentDescription ?: node.text)?.toString()?.trim()
            if (d != null && d.length < 20 && f.homeKw.any { d.startsWith(it, true) }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    /** 偵錯:6 秒後擷取當前畫面的元件 ID(給使用者切到目標 App 用) */
    fun dumpLater() {
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) { Guard.lastDump = "無法讀取畫面"; return@postDelayed }
            val ids = LinkedHashSet<String>(); val sel = ArrayList<String>()
            val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var n = 0
            while (q.isNotEmpty() && n < 1500) {
                val node = q.removeFirst(); n++
                node.viewIdResourceName?.let { ids.add(it.substringAfter(":id/")) }
                if (node.isSelected) (node.contentDescription ?: node.text)?.toString()?.let { if (it.length < 30) sel.add(it) }
                for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
            }
            Guard.lastDump = "套件:${root.packageName}\n被選取元件:${sel.take(15).joinToString(" | ")}\n\nID(${ids.size}):\n${ids.take(80).joinToString(", ")}"
            Toast.makeText(this, "已擷取,請回「手機監測」查看", Toast.LENGTH_LONG).show()
        }, 6000)
    }
}

/** 封鎖畫面 */
class BlockActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val pkg = intent.getStringExtra("pkg") ?: ""
        val reason = intent.getStringExtra("reason") ?: ""
        val canSnooze = intent.getBooleanExtra("snooze", false) && !Prefs.strict(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Theme.BG); setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val icon = android.widget.ImageView(this).apply { setImageDrawable(Apps.icon(this@BlockActivity, pkg)) }
        root.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(Ui.tv(this, "${Apps.label(this, pkg)} 已被封鎖", 22f, Theme.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(this, Ui.WRAP, Ui.WRAP, 16, 6))
        root.addView(Ui.tv(this, reason, 14f, Theme.SUB).apply { gravity = Gravity.CENTER }, Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 4))
        root.addView(Ui.tv(this, "休息一下,做點別的事吧。", 13f, Theme.SUB).apply { gravity = Gravity.CENTER }, Ui.lp(this, Ui.WRAP, Ui.WRAP, 0, 28))
        root.addView(Ui.btn(this, "返回桌面", Theme.GREEN, Color_DARK) { goHome() }, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 10))

        if (canSnooze) {
            val sb = Ui.btn(this, "", Theme.CARD2, Theme.SUB) {}
            var left = 10
            val tick = object : Runnable {
                override fun run() {
                    if (left > 0) { sb.text = "再給我 5 分鐘(請先等 $left 秒)"; left--; h.postDelayed(this, 1000) }
                    else {
                        sb.text = "再給我 5 分鐘"; sb.setTextColor(Theme.TEXT)
                        sb.setOnClickListener {
                            Guard.snooze(pkg, 5 * 60_000L)
                            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                            finish()
                        }
                    }
                }
            }
            h.post(tick)
            root.addView(sb, Ui.lp(this, Ui.MATCH, Ui.WRAP))
        }
        setContentView(root)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goHome() }
    override fun onStop() { super.onStop(); h.removeCallbacksAndMessages(null); finish() }

    companion object { val Color_DARK = android.graphics.Color.parseColor("#06251A") }
}
