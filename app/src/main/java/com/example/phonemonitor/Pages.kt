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
// 應用程式使用時間與控制
// =====================================================================
class AppsPage(a: Activity) : BasePage(a, "應用程式使用") {
    private var ri = 0
    private val chips = Chips(a, listOf("今天", "近 7 天"), 0) { ri = it; load() }

    init {
        col.addView(chips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        body.removeAllViews()
        if (!UsageAccess.granted(ctx)) { body.addView(Ui.usageBanner(ctx)); return }
        loading()
        bg {
            val now = System.currentTimeMillis()
            val from = if (ri == 0) AppUsage.dayStart(now) else now - 7 * AppUsage.DAY
            val r = AppUsage.compute(ctx, from, now)
            ui.post { render(r) }
        }
    }

    private fun render(r: UsageResult) {
        body.removeAllViews()
        val total = Prefs.totalLimit(ctx)
        val screenMin = r.screenOnMs / 60_000L
        val sc = MetricCard(ctx, "螢幕使用時間")
        sc.set(fmtDur(r.screenOnMs), "解鎖 ${r.unlocks} 次",
            if (ri == 0 && total > 0) (screenMin.toFloat() / total).coerceIn(0f, 1f) else null, Theme.usage(screenMin.toFloat() / maxOf(1, total)))
        val per = MetricCard(ctx, "平均每次解鎖", false)
        per.set(if (r.unlocks > 0) fmtDur(r.screenOnMs / r.unlocks) else "—", if (ri == 1) "平均每日 ${fmtDur(r.screenOnMs / 7)}" else "每次拿起手機的使用時間", null)
        val top = r.apps.firstOrNull { Apps.launchable(ctx, it.pkg) }
        val tp = MetricCard(ctx, "最常使用", false)
        tp.set(top?.let { Apps.label(ctx, it.pkg) } ?: "—", top?.let { fmtDur(it.fgMs) } ?: "", null)
        val cnt = MetricCard(ctx, "使用過的 App", false)
        cnt.set("${r.apps.size} 個", "超過 1 分鐘者", null)
        Ui.grid(ctx, body, sc, per, tp, cnt)

        val bar = BarChartView(ctx).apply {
            color = Theme.BLUE; emptyText = "尚無螢幕使用紀錄"
            fmt = { minFmt(it) }; maxFmt = { minFmt(it) }
        }
        if (ri == 0) {
            bar.title = "各時段螢幕使用"
            bar.set((0 until 24).map { "$it" }, r.hourMs.map { it / 60_000f })
        } else {
            bar.title = "每日螢幕時間"
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val origin = AppUsage.dayStart(System.currentTimeMillis()) - 6 * AppUsage.DAY
            bar.set((0 until 7).map { sdf.format(Date(origin + it * AppUsage.DAY + 3_600_000L)) }, r.dayMs.map { it / 60_000f })
        }
        body.addView(Ui.chartCard(ctx, bar, 190), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))

        // 螢幕時間提醒
        val lc = Ui.card(ctx)
        lc.addView(Ui.tv(ctx, "每日螢幕時間提醒", 15f, Theme.TEXT, true))
        lc.addView(Ui.tv(ctx, if (total > 0) "目前上限:$total 分鐘(用到 90% 與超過時會通知)" else "尚未設定。設定後會在快到上限與超過時發出通知。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
        lc.addView(Ui.btn(ctx, "設定每日螢幕時間上限", Theme.CARD2, Theme.TEXT) { pickLimit("每日螢幕時間上限") { Prefs.setTotalLimit(ctx, it); load() } })
        body.addView(lc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))

        body.addView(Ui.section(ctx, "各 App 使用時間(點選可設定上限 / 管理)"))
        val limits = Prefs.limits(ctx)
        val list = Ui.card(ctx)
        val apps = r.apps.filter { Apps.launchable(ctx, it.pkg) }.take(30)
        if (apps.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有足夠的使用紀錄。"))
        val maxMs = (apps.firstOrNull()?.fgMs ?: 1L).toFloat()
        for (s in apps) {
            val lim = limits[s.pkg] ?: 0
            val over = ri == 0 && lim > 0 && s.fgMs / 60_000L >= lim
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg),
                "${s.launches} 次開啟 · 最後使用 " + fmtTime(s.last, if (ri == 0) "HH:mm" else "MM/dd HH:mm"),
                fmtDur(s.fgMs), s.fgMs / maxMs, if (over) Theme.RED else Theme.BLUE,
                if (lim > 0) (if (over) "已超過上限" else "上限 $lim 分") else "", if (over) Theme.RED else Theme.AMBER)
            row.setOnClickListener { appMenu(s.pkg) }
            list.addView(row)
        }
        body.addView(list)
        body.addView(Ui.note(ctx, "說明:Android 不允許一般 App 強制關閉其他 App,所以「控制」採用通知提醒 + 一鍵跳到該 App 的資訊頁(可強制停止、停用、解除安裝)。"))
    }

    private fun pickLimit(title: String, onPick: (Int) -> Unit) {
        val names = arrayOf("不限制", "15 分鐘", "30 分鐘", "1 小時", "2 小時", "3 小時", "4 小時", "6 小時")
        val mins = intArrayOf(0, 15, 30, 60, 120, 180, 240, 360)
        AlertDialog.Builder(ctx).setTitle(title).setItems(names) { _, w -> onPick(mins[w]) }.show()
    }

    private fun appMenu(pkg: String) {
        val label = Apps.label(ctx, pkg)
        AlertDialog.Builder(ctx).setTitle(label)
            .setItems(arrayOf("設定每日使用上限", "App 資訊(強制停止 / 權限 / 停用)", "解除安裝")) { _, w ->
                when (w) {
                    0 -> pickLimit("$label 每日上限") { Prefs.setLimit(ctx, pkg, it); load() }
                    1 -> Apps.openInfo(ctx, pkg)
                    2 -> Apps.uninstall(ctx, pkg)
                }
            }.show()
    }
}

// =====================================================================
// 網路與行動網路用量
// =====================================================================
class NetPage(a: Activity) : BasePage(a, "網路用量") {
    private var pi = 1       // 0 今天 / 1 7天 / 2 30天 / 3 本月
    private var ti = 0       // 0 行動網路 / 1 Wi-Fi
    private val periodChips = Chips(a, listOf("今天", "7 天", "30 天", "本月"), 1) { pi = it; load() }
    private val typeChips = Chips(a, listOf("行動網路", "Wi-Fi"), 0) { ti = it; load() }

    init {
        col.addView(periodChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 8))
        col.addView(typeChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    private class Data(
        val mobile: LongArray?, val wifi: LongArray?, val cycleMobile: Long?,
        val apps: List<NetUsage.App>?, val barLabels: List<String>, val barValues: List<Float>, val err: String
    )

    override fun load() {
        body.removeAllViews()
        if (!UsageAccess.granted(ctx)) { body.addView(Ui.usageBanner(ctx)); return }
        loading()
        val period = pi; val type = if (ti == 0) NetUsage.MOBILE else NetUsage.WIFI
        bg {
            val now = System.currentTimeMillis()
            val today = AppUsage.dayStart(now)
            val cycle = NetUsage.cycleStart(now, Prefs.billingDay(ctx))
            val from = when (period) { 0 -> today; 1 -> today - 6 * AppUsage.DAY; 2 -> today - 29 * AppUsage.DAY; else -> cycle }
            var err = ""
            fun <T> tryq(f: () -> T): T? = try { f() } catch (e: Throwable) { err = "${e.javaClass.simpleName}: ${e.message}"; null }

            val mobile = tryq { NetUsage.total(ctx, NetUsage.MOBILE, from, now) }
            val wifi = tryq { NetUsage.total(ctx, NetUsage.WIFI, from, now) }
            val cm = tryq { NetUsage.total(ctx, NetUsage.MOBILE, cycle, now) }
            val apps = tryq { NetUsage.perApp(ctx, type, from, now) }

            val labels = ArrayList<String>(); val vals = ArrayList<Float>()
            if (period == 0) {
                val sdf = SimpleDateFormat("H", Locale.getDefault())
                for (h in 0 until 24) {
                    val a0 = today + h * 3_600_000L
                    if (a0 > now) break
                    val v = tryq { NetUsage.total(ctx, type, a0, minOf(a0 + 3_600_000L, now)) }
                    labels.add(sdf.format(Date(a0))); vals.add(if (v == null) 0f else (v[0] + v[1]).toFloat())
                }
            } else {
                val sdf = SimpleDateFormat("M/d", Locale.getDefault())
                var d0 = AppUsage.dayStart(from)
                while (d0 <= now && labels.size < 31) {
                    val v = tryq { NetUsage.total(ctx, type, d0, minOf(d0 + AppUsage.DAY, now)) }
                    labels.add(sdf.format(Date(d0 + 3_600_000L))); vals.add(if (v == null) 0f else (v[0] + v[1]).toFloat())
                    d0 += AppUsage.DAY
                }
            }
            val data = Data(mobile, wifi, cm?.let { it[0] + it[1] }, apps, labels, vals, err)
            ui.post { render(data, type) }
        }
    }

    private fun render(d: Data, type: Int) {
        body.removeAllViews()
        if (d.mobile == null && d.wifi == null) {
            body.addView(Ui.note(ctx, "無法讀取網路用量:${d.err}\n(部分機型或系統版本限制了行動網路統計)", Theme.AMBER)); return
        }
        val m = MetricCard(ctx, "行動網路", false)
        m.set(d.mobile?.let { fmtBytes(it[0] + it[1]) } ?: "—", d.mobile?.let { "↓ ${fmtBytes(it[0])}  ↑ ${fmtBytes(it[1])}" } ?: "不支援", null)
        val w = MetricCard(ctx, "Wi-Fi", false)
        w.set(d.wifi?.let { fmtBytes(it[0] + it[1]) } ?: "—", d.wifi?.let { "↓ ${fmtBytes(it[0])}  ↑ ${fmtBytes(it[1])}" } ?: "不支援", null)

        val apps = d.apps ?: emptyList()
        val fg = apps.sumOf { it.fg }; val bg = apps.sumOf { it.bg }
        val bgRatio = if (fg + bg > 0) bg.toFloat() / (fg + bg) else 0f
        val bgCard = MetricCard(ctx, "背景流量占比(${if (type == 0) "行動" else "Wi-Fi"})")
        bgCard.set("%.0f%%".format(bgRatio * 100), "前景 ${fmtBytes(fg)} · 背景 ${fmtBytes(bg)}", bgRatio, Theme.usage(bgRatio / 0.6f))

        val limitGb = Prefs.dataLimitGb(ctx)
        val used = d.cycleMobile ?: 0L
        val lim = MetricCard(ctx, "本月行動數據(結算日 ${Prefs.billingDay(ctx)} 號)")
        if (limitGb > 0) {
            val p = used / (limitGb * 1_073_741_824f)
            lim.set("%.0f%%".format(p * 100), "${fmtBytes(used)} / %.0f GB".format(limitGb), p.coerceIn(0f, 1f), Theme.usage(p))
        } else lim.set(fmtBytes(used), "尚未設定月流量上限", null)
        Ui.grid(ctx, body, m, w, bgCard, lim)

        val bar = BarChartView(ctx).apply {
            title = (if (type == 0) "行動網路" else "Wi-Fi") + (if (pi == 0) " 每小時用量" else " 每日用量")
            color = if (type == 0) Theme.AMBER else Theme.BLUE; emptyText = "這段期間沒有流量"
            fmt = { fmtBytes(it.toLong()) }; maxFmt = { fmtBytes(it.toLong()) }
            set(d.barLabels, d.barValues)
        }
        body.addView(Ui.chartCard(ctx, bar, 190), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))

        val sc = Ui.card(ctx)
        sc.addView(Ui.tv(ctx, "流量設定", 15f, Theme.TEXT, true))
        sc.addView(Ui.btn(ctx, "設定每月行動數據上限", Theme.CARD2, Theme.TEXT) {
            val names = arrayOf("不設定", "1 GB", "3 GB", "5 GB", "10 GB", "20 GB", "50 GB", "100 GB")
            val gbs = floatArrayOf(0f, 1f, 3f, 5f, 10f, 20f, 50f, 100f)
            AlertDialog.Builder(ctx).setTitle("每月行動數據上限").setItems(names) { _, i -> Prefs.setDataLimitGb(ctx, gbs[i]); load() }.show()
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 8))
        sc.addView(Ui.btn(ctx, "設定帳單結算日", Theme.CARD2, Theme.TEXT) {
            val days = intArrayOf(1, 5, 10, 15, 20, 25, 28)
            AlertDialog.Builder(ctx).setTitle("每月幾號結算").setItems(days.map { "$it 號" }.toTypedArray()) { _, i -> Prefs.setBillingDay(ctx, days[i]); load() }.show()
        })
        body.addView(sc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))

        body.addView(Ui.section(ctx, "各 App 用量排行(${if (type == 0) "行動網路" else "Wi-Fi"})"))
        val list = Ui.card(ctx)
        val top = apps.take(25)
        if (top.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有流量紀錄。"))
        val maxB = (top.firstOrNull()?.total ?: 1L).toFloat()
        for (s in top) {
            val (name, pkg) = NetUsage.uidName(ctx, s.uid)
            val heavyBg = s.bg > s.total * 0.5 && s.bg > 30L * 1_048_576
            val row = AppRow(ctx)
            row.set(pkg?.let { Apps.icon(ctx, it) }, name, "前景 ${fmtBytes(s.fg)} · 背景 ${fmtBytes(s.bg)}",
                fmtBytes(s.total), s.total / maxB, if (heavyBg) Theme.RED else Theme.BLUE,
                if (heavyBg) "背景偏高" else "", Theme.RED)
            if (pkg != null) row.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle(name).setMessage("可到 App 資訊 →「行動數據使用」關閉「背景數據」,阻止它偷跑流量。")
                    .setPositiveButton("開啟 App 資訊") { _, _ -> Apps.openInfo(ctx, pkg) }.setNegativeButton("取消", null).show()
            }
            list.addView(row)
        }
        body.addView(list)
        body.addView(Ui.note(ctx, "「背景偏高」= 背景流量超過一半且大於 30 MB,可能是自動同步、廣告或可疑上傳。"))
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
