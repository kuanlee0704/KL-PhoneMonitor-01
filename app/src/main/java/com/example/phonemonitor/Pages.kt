package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class BasePage(protected val ctx: Activity, title: String) {
    private val built = Ui.page(ctx)
    val view: ScrollView = built.first
    protected val col: LinearLayout = built.second
    protected val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    protected val ui = Handler(Looper.getMainLooper())

    init { col.addView(Ui.tv(ctx, title, 24f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12)) }

    abstract fun load()

    protected fun bg(work: () -> Unit) {
        Thread {
            try { work() } catch (e: Throwable) {
                ui.post { fail("${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    protected open fun fail(msg: String) {
        body.removeAllViews()
        body.addView(Ui.note(ctx, "讀取失敗:$msg", Theme.RED))
    }

    protected fun loading() { body.removeAllViews(); body.addView(Ui.note(ctx, "讀取中…")) }
    protected fun fmtTime(ts: Long, pattern: String = "MM/dd HH:mm") = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    protected fun minFmt(v: Float) = if (v >= 60f) "%.1f 時".format(v / 60f) else "%.0f 分".format(v)
}

// =====================================================================
// 電池分析
// =====================================================================
class BatteryPage(a: Activity) : BasePage(a, "電池分析") {
    private val ranges = longArrayOf(7, 30, 90)
    private var ri = 1
    private val chips = Chips(a, listOf("7 天", "30 天", "90 天"), 1) { ri = it; load() }

    init {
        col.addView(chips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        loading()
        val days = ranges[ri]
        bg {
            val rows = Db.get(ctx).since(System.currentTimeMillis() - days * 86_400_000L)
            val an = BatteryAnalysis.compute(rows)
            ui.post { render(an, days) }
        }
    }

    private fun render(a: BatAnalysis?, days: Long) {
        body.removeAllViews()
        if (a == null) {
            body.addView(Ui.note(ctx, "資料不足。請先到「設定」開始監測,累積一段時間(建議至少一天、包含一次充電)後再來看。", Theme.AMBER))
            return
        }
        fun r1(v: Float) = if (v >= 0) "%.1f".format(v) else "—"

        val runtime = MetricCard(ctx, "預估滿電續航")
        runtime.set(if (a.rate > 0) "%.1f 小時".format(100f / a.rate) else "—",
            if (a.rate > 0) "整體 ${r1(a.rate)} %/時" else "放電資料不足", null)
        val on = MetricCard(ctx, "亮屏耗電")
        on.set(if (a.onRate >= 0) "${r1(a.onRate)} %/時" else "—",
            if (a.onRate > 0) "亮屏約可用 %.1f 小時".format(100f / a.onRate) else "亮屏資料不足",
            if (a.onRate >= 0) (a.onRate / 25f).coerceIn(0f, 1f) else null, Theme.usage(a.onRate / 25f))
        val off = MetricCard(ctx, "待機耗電(息屏)")
        off.set(if (a.offRate >= 0) "${r1(a.offRate)} %/時" else "—",
            if (a.offRate > 1.5f) "偏高,可能有 App 背景耗電" else if (a.offRate >= 0) "待機表現正常" else "息屏資料不足",
            if (a.offRate >= 0) (a.offRate / 3f).coerceIn(0f, 1f) else null, Theme.usage(a.offRate / 3f))
        val cs = MetricCard(ctx, "充電速度")
        cs.set(if (a.chargeRate >= 0) "${r1(a.chargeRate)} %/時" else "—",
            "80% 以下:" + (if (a.chargeRate80 >= 0) "${r1(a.chargeRate80)} %/時" else "—"), null)
        val cw = MetricCard(ctx, "平均充電功率")
        cw.set(if (a.avgChargeW >= 0) "%.1f W".format(a.avgChargeW) else "—", "依電流 × 電壓換算", null)
        val ct = MetricCard(ctx, "充電時溫度")
        ct.set(if (a.avgChargeTemp > -50) "%.1f °C".format(a.avgChargeTemp) else "—",
            "最高 " + (if (a.maxChargeTemp > -50) "%.1f °C".format(a.maxChargeTemp) else "—"),
            if (a.maxChargeTemp > -50) (a.maxChargeTemp / 50f).coerceIn(0f, 1f) else null, Theme.usage(a.maxChargeTemp / 45f))
        val eq = MetricCard(ctx, "等效完整循環")
        eq.set("%.1f 次".format(a.eqCycles), "累計耗電 ${a.totalDrain}%(近 $days 天)", null)
        val cc = MetricCard(ctx, "充電次數")
        cc.set("${a.chargeCount} 次", "平均每日 %.1f 次".format(a.chargeCount / a.days), null)
        val lv = MetricCard(ctx, "平均插電電量")
        lv.set(if (a.avgStartLv >= 0) "%.0f%%".format(a.avgStartLv) else "—",
            "平均充到 " + (if (a.avgEndLv >= 0) "%.0f%%".format(a.avgEndLv) else "—") + " 拔掉",
            if (a.avgStartLv >= 0) a.avgStartLv / 100f else null, Theme.good(a.avgStartLv / 100f))
        val dp = MetricCard(ctx, "深度放電")
        dp.set("${a.deepCount} 次", "放電到 10% 以下", null)
        val ratio = if (a.chargeMs > 0) a.fullPlugMs.toFloat() / a.chargeMs else 0f
        val fp = MetricCard(ctx, "滿電後仍插電")
        fp.set("%.0f%%".format(ratio * 100), "共 ${fmtDur(a.fullPlugMs)}(占充電時間)", ratio, Theme.usage(ratio / 0.6f))
        Ui.grid(ctx, body, runtime, on, off, cs, cw, ct, eq, cc, lv, dp, fp)

        // 建議
        val tips = ArrayList<String>()
        if (ratio > 0.35f) tips.add("有 ${(ratio * 100).toInt()}% 的充電時間停留在 100%。長期滿電會加速老化,建議開啟系統的「充電上限 80%」或「睡眠充電/適應性充電」。")
        if (a.maxChargeTemp >= 40f) tips.add("充電時電池最高達 %.1f°C。建議避免邊充邊玩、拆掉厚保護殼、不要在被子或車內高溫處充電。".format(a.maxChargeTemp))
        if (a.deepCount >= 3) tips.add("近 $days 天有 ${a.deepCount} 次放電到 10% 以下。鋰電池深放電較傷,建議 20% 前就充電。")
        if (a.avgStartLv in 0f..15f && a.chargeCount >= 3) tips.add("你平均在 %.0f%% 才插電,偏低。把插電時機提早到 20~30% 有助延壽。".format(a.avgStartLv))
        if (a.offRate > 1.5f) tips.add("待機耗電 %.1f %%/時 偏高(正常多在 1 以下)。到「應用」與「隱私」頁檢查是否有 App 在背景常駐。".format(a.offRate))
        if (tips.isEmpty()) tips.add("目前的充電與用電習慣良好,繼續保持。")
        val tc = Ui.card(ctx)
        tc.addView(Ui.tv(ctx, "充電與用電建議", 15f, Theme.TEXT, true))
        tips.forEach { tc.addView(Ui.tv(ctx, "• $it", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0)) }
        body.addView(tc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))

        // 時段耗電
        val hb = BarChartView(ctx).apply {
            title = "各時段平均耗電(每日)"; color = Theme.PURPLE; emptyText = "尚無放電資料"
            fmt = { "%.1f".format(it) }; maxFmt = { "%.1f%%".format(it) }
            set((0 until 24).map { "$it" }, a.hourDrain.toList())
        }
        body.addView(Ui.chartCard(ctx, hb, 190), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 容量趨勢
        val cap = ChartView(ctx).apply {
            title = "推估電池容量"; unit = " mAh"; colorNormal = Theme.GREEN; longRange = true
            setData(a.capPts)
        }
        body.addView(Ui.chartCard(ctx, cap, 170), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
        body.addView(Ui.note(ctx, "容量由「剩餘電荷 ÷ 電量%」推估,僅供趨勢參考;若持續下滑代表電池老化。"))

        // 區間列表
        body.addView(Ui.section(ctx, "最近充放電紀錄"))
        val sc = Ui.card(ctx)
        val recent = a.sessions.sortedByDescending { it.start }.take(14)
        if (recent.isEmpty()) sc.addView(Ui.note(ctx, "尚無完整紀錄"))
        recent.forEach { s ->
            val dur = fmtDur(s.end - s.start)
            val sub = if (s.charging)
                "充電 ${s.lv0}% → ${s.lv1}% · $dur" + (if (s.avgW >= 0) " · %.1f W".format(s.avgW) else "") + " · 最高 %.0f°C".format(s.maxT)
            else {
                val hrs = (s.end - s.start) / 3_600_000f
                "放電 ${s.lv0}% → ${s.lv1}% · $dur" + (if (hrs > 0.2f) " · %.1f %%/時".format((s.lv0 - s.lv1) / hrs) else "") +
                    " · 亮屏 ${fmtDur(s.screenMs)}"
            }
            sc.addView(Ui.tv(ctx, (if (s.charging) "⚡ " else "🔋 ") + fmtTime(s.start) + " → " + fmtTime(s.end, "HH:mm"), 13f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            sc.addView(Ui.tv(ctx, sub, 11f, Theme.SUB))
        }
        body.addView(sc)
    }
}

// =====================================================================
// 隱私與權限偵測
// =====================================================================
class PrivacyPage(a: Activity) : BasePage(a, "隱私與權限") {
    private var fi = 0
    private val chips = Chips(a, listOf("全部", "高風險", "閒置有權限"), 0) { fi = it; load() }

    init { col.addView(body) }

    private class Data(val audit: AuditResult, val events: List<Ev>, val fgs: List<FgsStat>)

    override fun load() {
        loading()
        bg {
            val now = System.currentTimeMillis()
            val audit = PermAudit.scan(ctx)
            val ev = Db.get(ctx).events(now - 7 * AppUsage.DAY)
            val fgs = if (UsageAccess.granted(ctx)) try { AppUsage.aggregate(ctx, now - 7 * AppUsage.DAY, now) } catch (e: Throwable) { emptyList() } else emptyList()
            ui.post { render(Data(audit, ev, fgs)) }
        }
    }

    private fun render(d: Data) {
        body.removeAllViews()
        val intro = Ui.card(ctx)
        intro.addView(Ui.tv(ctx, "這一頁能做什麼", 15f, Theme.TEXT, true))
        intro.addView(Ui.tv(ctx,
            "• 偵測麥克風 / 相機被使用的時間點(需開啟背景監測),螢幕關閉時使用會警示\n" +
                "• 盤點哪些 App 拿了敏感權限、或啟用了無障礙 / 通知存取 / 裝置管理員等高風險特殊權限\n" +
                "• 找出「閒置卻握有權限」與「長時間背景常駐」的 App\n\n" +
                "限制:Android 不開放一般 App 讀取「某 App 在何時存取了相機/麥克風/位置」的完整歷史,那份最準的紀錄在系統的「權限使用紀錄」(Android 12+),下方可一鍵開啟。", 12f, Theme.SUB),
            Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0))
        body.addView(intro, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // ---- 麥克風 / 相機事件 ----
        body.addView(Ui.section(ctx, "麥克風 / 相機 使用紀錄(近 7 天)"))
        val sus = d.events.count { it.flag == 2 }
        val mic = d.events.count { it.kind == "mic" }; val cam = d.events.count { it.kind == "cam" }
        val m1 = MetricCard(ctx, "麥克風使用", false); m1.set("$mic 次", "含通話與語音", null)
        val m2 = MetricCard(ctx, "相機使用", false); m2.set("$cam 次", "含前後鏡頭", null)
        val m3 = MetricCard(ctx, "螢幕關閉時使用", false); m3.set("$sus 次", if (sus > 0) "請逐一確認是否為你本人" else "沒有異常", null)
        Ui.grid(ctx, body, m1, m2, m3, View(ctx))
        val ec = Ui.card(ctx)
        if (!Prefs.enabled(ctx)) ec.addView(Ui.note(ctx, "背景監測未開啟,目前不會記錄。請到「設定」開始監測。", Theme.AMBER))
        if (d.events.isEmpty()) ec.addView(Ui.note(ctx, "近 7 天沒有紀錄。"))
        d.events.take(15).forEach { e ->
            val what = if (e.kind == "mic") "🎙 麥克風" else "📷 相機"
            val dur = if (e.endTs > 0) fmtDur(e.endTs - e.ts).let { if (e.endTs - e.ts < 60_000L) "${(e.endTs - e.ts) / 1000} 秒" else it } else "進行中/未知"
            val head = (if (e.flag == 2) "⚠ " else "") + what + "  " + fmtTime(e.ts) + "  ($dur)"
            val fgName = if (e.fg.isNotEmpty()) Apps.label(ctx, e.fg) else "未知"
            ec.addView(Ui.tv(ctx, head, 13f, if (e.flag == 2) Theme.RED else Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            ec.addView(Ui.tv(ctx, "${e.info} · 螢幕${if (e.screenOn) "亮" else "熄"} · 當時前景:$fgName", 11f, Theme.SUB))
        }
        body.addView(ec, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 4))

        // ---- 高風險特殊權限 ----
        body.addView(Ui.section(ctx, "高風險特殊權限(監控軟體常用)"))
        val spc = Ui.card(ctx)
        if (d.audit.specials.isEmpty()) spc.addView(Ui.note(ctx, "沒有發現第三方 App 啟用這些特殊權限。"))
        val explain = mapOf(
            "無障礙服務" to "可讀取螢幕內容、代你操作", "通知存取" to "可讀取所有通知(含驗證碼)",
            "裝置管理員" to "可鎖機、清除資料,難以解除安裝", "懸浮視窗" to "可蓋在其他 App 上方(釣魚常用)",
            "可安裝其他 App" to "可靜默下載安裝程式"
        )
        d.audit.specials.sortedBy { it.kind }.forEach { s ->
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg), explain[s.kind] ?: "", "", null, Theme.BLUE, s.kind,
                if (s.kind == "無障礙服務" || s.kind == "裝置管理員") Theme.RED else Theme.AMBER)
            row.setOnClickListener { riskMenu(s.pkg) }
            spc.addView(row)
        }
        body.addView(spc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
        body.addView(Ui.note(ctx, "不認得的 App 若出現在這裡,請特別留意;自己安裝的(如密碼管理器、螢幕閱讀器)屬正常。"))

        // ---- 敏感權限稽核 ----
        body.addView(Ui.section(ctx, "敏感權限稽核(使用者安裝的 App)"))
        body.addView(chips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        val now = System.currentTimeMillis()
        val list = d.audit.apps.filter {
            when (fi) {
                1 -> it.risk == 2
                2 -> d.audit.usageKnown && it.score >= 3 && (it.lastUsed == 0L || now - it.lastUsed > 14 * AppUsage.DAY)
                else -> true
            }
        }.take(40)
        val ac = Ui.card(ctx)
        if (!d.audit.usageKnown) ac.addView(Ui.note(ctx, "尚未開啟「使用情況存取權」,無法判斷「最後使用時間」。", Theme.AMBER))
        if (list.isEmpty()) ac.addView(Ui.note(ctx, "沒有符合條件的 App。"))
        for (p in list) {
            val used = if (!d.audit.usageKnown) "" else if (p.lastUsed == 0L) " · 30 天未使用" else " · 最後使用 " + fmtTime(p.lastUsed, "MM/dd")
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, p.pkg), p.label, (p.perms + p.specials).take(5).joinToString("、") + used, "", (p.score / 12f).coerceIn(0f, 1f),
                when (p.risk) { 2 -> Theme.RED; 1 -> Theme.AMBER; else -> Theme.GREEN },
                when (p.risk) { 2 -> "高"; 1 -> "中"; else -> "低" }, when (p.risk) { 2 -> Theme.RED; 1 -> Theme.AMBER; else -> Theme.GREEN })
            row.setOnClickListener { riskMenu(p.pkg, p) }
            ac.addView(row)
        }
        body.addView(ac)

        // ---- 背景常駐 ----
        body.addView(Ui.section(ctx, "背景常駐(前景服務)時間 · 近 7 天"))
        val fc = Ui.card(ctx)
        val fgs = d.fgs.filter { it.fgsMs >= 10 * 60_000L && it.pkg != ctx.packageName }.sortedByDescending { it.fgsMs }.take(10)
        if (!d.audit.usageKnown) fc.addView(Ui.note(ctx, "需要「使用情況存取權」。", Theme.AMBER))
        else if (fgs.isEmpty()) fc.addView(Ui.note(ctx, "沒有 App 長時間在背景常駐。"))
        val maxF = (fgs.firstOrNull()?.fgsMs ?: 1L).toFloat()
        for (f in fgs) {
            val hidden = f.fgsMs > f.fgMs * 3
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, f.pkg), Apps.label(ctx, f.pkg), "畫面上使用 ${fmtDur(f.fgMs)}", fmtDur(f.fgsMs), f.fgsMs / maxF,
                if (hidden) Theme.RED else Theme.BLUE, if (hidden) "多在背景" else "", Theme.RED)
            row.setOnClickListener { riskMenu(f.pkg) }
            fc.addView(row)
        }
        body.addView(fc)
        body.addView(Ui.note(ctx, "「多在背景」= 背景常駐時間遠大於你實際在畫面上使用的時間。音樂、導航、運動 App 屬正常。"))

        // ---- 系統入口 ----
        body.addView(Ui.section(ctx, "系統隱私工具"))
        val tools = Ui.card(ctx)
        fun tool(label: String, intent: () -> Intent) {
            tools.addView(Ui.btn(ctx, label, Theme.CARD2, Theme.TEXT) {
                try { ctx.startActivity(intent()) } catch (e: Exception) { Toast.makeText(ctx, "此手機不支援這個設定頁", Toast.LENGTH_SHORT).show() }
            }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        }
        if (Build.VERSION.SDK_INT >= 31) tool("權限使用紀錄(系統,最準)") { Intent("android.intent.action.REVIEW_PERMISSION_HISTORY") }
        tool("無障礙設定") { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        tool("通知存取設定") { Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }
        tool("裝置管理員 / 安全性設定") { Intent(Settings.ACTION_SECURITY_SETTINGS) }
        tool("懸浮視窗存取") { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        tool("安裝未知來源 App") { Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
        body.addView(tools)
    }

    private fun riskMenu(pkg: String, p: AppPerm? = null) {
        val label = Apps.label(ctx, pkg)
        val msg = if (p != null) "已授予:" + (p.perms + p.specials).joinToString("、") else null
        val b = AlertDialog.Builder(ctx).setTitle(label)
        if (msg != null) b.setMessage(msg)
        b.setItems(arrayOf("開啟 App 資訊(可撤銷權限)", "解除安裝")) { _, w ->
            if (w == 0) Apps.openInfo(ctx, pkg) else Apps.uninstall(ctx, pkg)
        }.show()
    }
}
