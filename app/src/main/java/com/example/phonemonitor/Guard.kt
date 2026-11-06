package com.example.phonemonitor

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
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

/**
 * 可獨立封鎖的「區塊」:同一個 App 內的 Reels/Shorts(kind 0)、主頁動態(kind 1)、探索(kind 2)可分別設定。
 * ids:播放器元件 ID(不含套件前綴);tabKw:底部分頁文字(需「被選取」才算)。
 * 偵測只用定點查詢(單次 IPC),不遍歷整棵畫面樹,避免拖慢目標 App。
 */
class Surface(
    val key: String, val app: String, val name: String, val pkgs: Set<String>,
    val kind: Int, val ids: List<String>, val tabKw: List<String>
)

object Surfaces {
    private val IG = setOf("com.instagram.android")
    private val YT = setOf("com.google.android.youtube")
    private val FB = setOf("com.facebook.katana", "com.facebook.lite")
    val homeKw = listOf("Home", "首頁", "主頁")
    private val profile = mapOf(
        "Instagram" to listOf("Profile", "個人檔案", "個人資料"),
        "YouTube" to listOf("You", "你", "Library", "媒體庫"),
        "Facebook" to listOf("Menu", "選單", "Profile", "個人檔案")
    )
    fun profileKw(app: String): List<String> = profile[app] ?: emptyList()

    // key 沿用舊版:ig / yt / fb 代表 Reels/Shorts
    val all = listOf(
        Surface("ig", "Instagram", "Reels 短影音", IG, 0, listOf("clips_viewer_view_pager", "clips_viewer_pager", "clips_swipe_refresh_container"), listOf("Reels", "連續短片", "短片")),
        Surface("igh", "Instagram", "主頁(首頁動態)", IG, 1, emptyList(), homeKw),
        Surface("ige", "Instagram", "探索 / 搜尋", IG, 2, emptyList(), listOf("Search and Explore", "Search", "搜尋和探索", "搜尋")),
        Surface("yt", "YouTube", "Shorts 短影音", YT, 0, listOf("reel_recycler", "reel_player_page_container"), listOf("Shorts")),
        Surface("yth", "YouTube", "首頁", YT, 1, emptyList(), homeKw),
        Surface("fb", "Facebook", "Reels 短影音", FB, 0, listOf("reels_viewer_pager"), listOf("Reels", "連續短片")),
        Surface("fbh", "Facebook", "首頁動態", FB, 1, emptyList(), homeKw)
    )
    val apps = listOf("Instagram", "YouTube", "Facebook")
    val byPkg: Map<String, List<Surface>> = all.flatMap { s -> s.pkgs.map { it to s } }.groupBy({ it.first }, { it.second })
    val TIKTOK = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
}

object Guard {
    private val snoozes = ConcurrentHashMap<String, Long>()
    private val usageCache = HashMap<String, Pair<Long, Long>>()
    @Volatile var lastDetect = "尚無偵測紀錄"
    @Volatile var lastDump = ""

    // 規則快取(避免每個無障礙事件都重讀設定)
    private var snapAt = 0L
    private var sBlocked: Set<String> = emptySet()
    private var sFocus: Set<String> = emptySet()
    private var sLimits: Map<String, Int> = emptyMap()
    private var sLimitBlock = false

    fun invalidate() { snapAt = 0L }

    private fun snap(ctx: Context) {
        val n = SystemClock.uptimeMillis()
        if (n - snapAt < 1500L) return
        snapAt = n
        sBlocked = Prefs.blocked(ctx); sFocus = Prefs.focusApps(ctx)
        sLimits = Prefs.limits(ctx); sLimitBlock = Prefs.limitBlock(ctx)
    }

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
        snap(ctx)
        if (pkg in sBlocked) return Block("你已將這個 App 設為永久封鎖", false)
        if (pkg in sFocus) {
            if (inFocus(ctx)) return Block("專注模式進行中", true)
            if (inSchedule(ctx)) return Block("目前是排程專注時段", true)
        }
        val lim = sLimits[pkg] ?: 0
        if (lim > 0 && sLimitBlock && UsageAccess.granted(ctx)) {
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
    private var settleUntil = 0L        // 剛處理完,這段時間內不再偵測(等轉場結束)
    private var lastUserEvent = 0L      // 最近一次「使用者操作」事件(處理後的轉場事件不算)
    private var lastExitAt = 0L
    private var stuck = 0               // 處理後沒有使用者操作、卻又偵測到 → 動作沒生效,逐級加強
    private var confirming = false
    private var ticking: Surface? = null
    private var lastToast = 0L

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
        val quick = type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SELECTED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (quick && now >= settleUntil) lastUserEvent = now

        // 1) 封鎖 / 專注 / 上限
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || now - lastCheck > 1500L) {
            lastCheck = now
            Guard.reason(this, pkg)?.let { block(pkg, it); return }
        }

        // 2) App 內區塊(Reels / 主頁 / 探索)
        val list = Surfaces.byPkg[pkg] ?: return
        val enabled = list.filter { Prefs.feature(this, it.key) >= 0 }
        if (enabled.isEmpty()) return
        if (now < settleUntil || confirming || ticking != null) return
        if (now - lastScan < (if (quick) 300L else 1500L)) return
        lastScan = now
        if (detectNow(enabled) == null) return
        // 需 400ms 後仍偵測到才處理,避免轉場瞬間誤判
        confirming = true
        handler.postDelayed({
            confirming = false
            detectNow(enabled)?.let { onSurface(it.first, it.second) }
        }, 400L)
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

    // ---------- 區塊偵測(定點查詢,不遍歷整棵樹) ----------
    private fun detectNow(enabled: List<Surface>): Pair<Surface, String>? {
        val root = rootInActiveWindow ?: return null
        val p = root.packageName?.toString() ?: return null
        val mine = enabled.filter { p in it.pkgs }
        if (mine.isEmpty()) return null
        return detect(root, p, mine)
    }

    private fun detect(root: AccessibilityNodeInfo, pkg: String, enabled: List<Surface>): Pair<Surface, String>? {
        val dm = resources.displayMetrics
        val sh = dm.heightPixels; val sw = dm.widthPixels
        val r = Rect()
        // (1) 全螢幕播放器元件(必須可見且夠大,首頁預覽小卡不算)
        for (s in enabled) {
            val custom = Prefs.customIds(this, s.key).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (id in s.ids + custom) {
                val full = if (id.contains(":")) id else "$pkg:id/$id"
                val list = try { root.findAccessibilityNodeInfosByViewId(full) } catch (e: Exception) { null } ?: continue
                for (n in list) {
                    if (!n.isVisibleToUser) continue
                    n.getBoundsInScreen(r)
                    if (r.height() > sh * 0.5 && r.width() > sw * 0.7) return Pair(s, "元件:$id")
                }
            }
        }
        // (2) 底部分頁列中「被選取」的分頁
        for (s in enabled) {
            for (kw in s.tabKw) {
                val list = try { root.findAccessibilityNodeInfosByText(kw) } catch (e: Exception) { null } ?: continue
                for (n in list) {
                    if (!(n.isSelected || n.parent?.isSelected == true)) continue
                    n.getBoundsInScreen(r)
                    if (r.top > sh * 0.78) return Pair(s, "分頁:$kw")
                }
            }
        }
        return null
    }

    private fun onSurface(s: Surface, hit: String) {
        Guard.lastDetect = "${s.app} ${s.name} · $hit · " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val mode = Prefs.feature(this, s.key)
        if (mode < 0) return
        if (mode > 0 && Prefs.featUsedMs(this, s.key) < mode * 60_000L) { startTicking(s); return }
        exitSurface(s)
    }

    // 額度模式:每 2 秒累計一次(也是單次查詢,不會拖慢目標 App)
    private fun startTicking(s: Surface) {
        if (ticking != null) return
        ticking = s
        handler.postDelayed(tick, 2000L)
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = ticking ?: return
            val mode = Prefs.feature(this@GuardService, s.key)
            if (mode < 0 || detectNow(listOf(s)) == null) { ticking = null; return }
            if (mode > 0) Prefs.addFeatUsed(this@GuardService, s.key, 2000L)
            if (mode == 0 || Prefs.featUsedMs(this@GuardService, s.key) >= mode * 60_000L) {
                ticking = null; exitSurface(s)
            } else handler.postDelayed(this, 2000L)
        }
    }

    /**
     * 離開該區塊。不會因為「被觸發很多次」而自動放行:
     * 使用者一直點進去 → 每次都擋;若處理後沒有任何使用者操作卻仍偵測到(動作沒生效),就逐級加強:
     * 點擊著陸分頁 → 返回鍵 → 回桌面。
     */
    private fun exitSurface(s: Surface) {
        val now = SystemClock.uptimeMillis()
        stuck = if (now - lastExitAt < 8000L && lastUserEvent <= lastExitAt) stuck + 1 else 0
        lastExitAt = now
        settleUntil = now + 2500L
        Prefs.incFeatBlocks(this, s.key)
        toast("${s.app} ${s.name} 已封鎖")
        when {
            stuck == 0 -> if (!clickLanding(s)) performGlobalAction(GLOBAL_ACTION_BACK)
            stuck == 1 -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun toast(msg: String) {
        val n = SystemClock.uptimeMillis()
        if (n - lastToast < 5000L) return
        lastToast = n
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 著陸位置:擋 Reels → 回首頁(若首頁也被擋就去個人檔案);擋首頁/探索 → 個人檔案 */
    private fun clickLanding(s: Surface): Boolean {
        val homeBlocked = Surfaces.all.any { it.app == s.app && it.kind == 1 && Prefs.feature(this, it.key) >= 0 }
        val kws = if (s.kind == 0 && !homeBlocked) Surfaces.homeKw else Surfaces.profileKw(s.app)
        val root = rootInActiveWindow ?: return false
        val sh = resources.displayMetrics.heightPixels
        val r = Rect()
        for (kw in kws) {
            val list = try { root.findAccessibilityNodeInfosByText(kw) } catch (e: Exception) { null } ?: continue
            for (n in list) {
                n.getBoundsInScreen(r)
                if (r.top < sh * 0.78) continue
                var x: AccessibilityNodeInfo? = n
                var depth = 0
                while (x != null && !x.isClickable && depth < 4) { x = x.parent; depth++ }
                if (x != null && x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    /** 偵錯:6 秒後擷取當前畫面的元件 ID(給使用者切到目標 App 用,僅使用者主動觸發時才遍歷) */
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
        val mins = Prefs.snoozeMin(this)

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

        val doSnooze = {
            Guard.snooze(pkg, mins * 60_000L)
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            finish()
        }
        if (canSnooze && Lock.hasPin(this)) {
            root.addView(Ui.btn(this, "輸入密碼,多給 $mins 分鐘", Theme.CARD2, Theme.TEXT) {
                Lock.prompt(this, "輸入密碼") { doSnooze() }
            }, Ui.lp(this, Ui.MATCH, Ui.WRAP))
        } else if (canSnooze) {
            val sb = Ui.btn(this, "", Theme.CARD2, Theme.SUB) {}
            var left = 10
            val tick = object : Runnable {
                override fun run() {
                    if (left > 0) { sb.text = "再給我 $mins 分鐘(請先等 $left 秒)"; left--; h.postDelayed(this, 1000) }
                    else { sb.text = "再給我 $mins 分鐘"; sb.setTextColor(Theme.TEXT); sb.setOnClickListener { doSnooze() } }
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
