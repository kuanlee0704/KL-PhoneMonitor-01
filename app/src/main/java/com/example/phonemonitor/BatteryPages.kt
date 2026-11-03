package com.example.phonemonitor

import android.app.Activity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 電池分析:概覽 / 放電 / 充電 / 健康 / 熱圖 */
class BatteryPage(a: Activity) : BasePage(a, "電池分析") {
    private val ranges = longArrayOf(7, 30, 90)
    private var ri = 1
    private var sub = 0
    private class D(val rows: List<Sample>, val an: BatAnalysis?, val deep: BatDeep?, val days: List<DayStat>, val last: Sample?, val span: Long)
    private var data: D? = null

    private val rangeChips = Chips(a, listOf("7 天", "30 天", "90 天"), 1) { ri = it; data = null; load() }
    private val subChips = Chips(a, listOf("概覽", "放電", "充電", "健康", "熱圖"), 0) { sub = it; render() }

    init {
        col.addView(rangeChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 8))
        col.addView(subChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        if (data == null) loading()
        val n = ranges[ri]
        bg {
            val now = System.currentTimeMillis()
            val rows = Db.get(ctx).since(now - n * AppUsage.DAY)
            val an = BatteryAnalysis.compute(rows)
            val deep = BatDeepAnalysis.compute(rows, now)
            val today = AppUsage.dayStart(now)
            val days = DayAgg.compute(rows, today - (n - 1) * AppUsage.DAY, today + AppUsage.DAY)
            ui.post { data = D(rows, an, deep, days, rows.lastOrNull(), n); render() }
        }
    }

    private fun render() {
        val d = data ?: return
        body.removeAllViews()
        if (d.an == null || d.deep == null) {
            body.addView(Ui.note(ctx, "資料不足。請先到「設定」開始監測,累積一段時間(建議至少一天、包含一次充電)後再來看。", Theme.AMBER)); return
        }
        when (sub) { 0 -> overview(d, d.an, d.deep); 1 -> discharge(d, d.an, d.deep); 2 -> charge(d, d.an, d.deep); 3 -> health(d, d.an, d.deep); else -> heat(d.deep) }
    }

    // ---------- 小工具 ----------
    private fun r1(v: Float) = if (v >= 0f) "%.1f".format(v) else "—"
    private fun chart(v: View, h: Int) = body.addView(Ui.chartCard(ctx, v, h), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
    private val dayFmt = SimpleDateFormat("M/d", Locale.getDefault())
    private fun dayLabels(d: D) = d.days.map { dayFmt.format(Date(it.day + 12 * 3_600_000L)) }

    private fun healthPct(s: Sample?): Int? {
        if (s == null) return null
        if (s.fullUah > 0 && s.designUah > 0) return minOf(100, s.fullUah * 100 / s.designUah)
        if (s.soh > 0) return minOf(100, s.soh)
        return null
    }

    private class Score(val total: Int, val standby: Int, val habit: Int, val temp: Int, val health: Int)

    private fun score(d: D, a: BatAnalysis): Score {
        fun cl(v: Float) = v.coerceIn(0f, 100f).toInt()
        val standby = if (a.offRate >= 0f) cl(100f - (a.offRate - 0.5f) / 2.5f * 100f) else 70
        val ratio = if (a.chargeMs > 0) a.fullPlugMs.toFloat() / a.chargeMs else 0f
        var habit = 100f - ratio * 80f - a.deepCount * 4f
        if (a.avgStartLv in 0f..12f) habit -= 8f
        val temp = if (a.avgChargeTemp > -50f) cl(100f - (a.avgChargeTemp - 33f) / 9f * 100f) else 80
        val health = healthPct(d.last) ?: 80
        val habitI = cl(habit)
        val total = (standby * 0.30f + habitI * 0.25f + temp * 0.20f + health * 0.25f).toInt()
        return Score(total, standby, habitI, temp, health)
    }

    // ---------- 概覽 ----------
    private fun overview(d: D, a: BatAnalysis, deep: BatDeep) {
        val sc = score(d, a)
        val hero = Ui.card(ctx, 24)
        val ring = RingView(ctx)
        ring.set(sc.total.toFloat(), "${sc.total}", "電池綜合評分", Theme.good(sc.total / 100f))
        hero.addView(ring, android.widget.LinearLayout.LayoutParams(ctx.dp(170), ctx.dp(170)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        val verdict = when { sc.total >= 85 -> "狀態優良"; sc.total >= 70 -> "狀態良好"; sc.total >= 50 -> "尚可,有改善空間"; else -> "建議留意充放電習慣" }
        hero.addView(Ui.tv(ctx, verdict, 14f, Theme.SUB).apply { gravity = android.view.Gravity.CENTER }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        fun comp(title: String, v: Int, sub: String): MetricCard =
            MetricCard(ctx, title).also { it.set("$v 分", sub, v / 100f, Theme.good(v / 100f)) }
        Ui.grid(ctx, body,
            comp("待機表現", sc.standby, if (a.offRate >= 0f) "息屏 ${r1(a.offRate)} %/時" else "資料不足"),
            comp("充電習慣", sc.habit, "滿電停留、深度放電"),
            comp("溫度控制", sc.temp, if (a.avgChargeTemp > -50f) "充電均溫 %.1f°C".format(a.avgChargeTemp) else "資料不足"),
            comp("電池健康", sc.health, healthPct(d.last)?.let { "容量約 $it%" } ?: "未提供,以 80 分估算"))

        body.addView(Ui.section(ctx, "重點數據"))
        kpis(a)

        val tips = ArrayList<String>()
        val ratio = if (a.chargeMs > 0) a.fullPlugMs.toFloat() / a.chargeMs else 0f
        if (ratio > 0.35f) tips.add("有 ${(ratio * 100).toInt()}% 的充電時間停留在 100%。長期滿電會加速老化,建議開啟系統的「充電上限 80%」或「睡眠充電」。")
        if (deep.overnightCount > 0 && deep.overnightFullH > 3f * deep.overnightCount) tips.add("夜間充電 ${deep.overnightCount} 次,平均每次約 %.1f 小時處於滿電。建議用定時插座或系統的適應性充電。".format(deep.overnightFullH / deep.overnightCount))
        if (a.maxChargeTemp >= 40f) tips.add("充電時電池最高達 %.1f°C。避免邊充邊玩、拆掉厚保護殼,不要在被子或車內高溫處充電。".format(a.maxChargeTemp))
        if (a.deepCount >= 3) tips.add("期間有 ${a.deepCount} 次放電到 10% 以下,鋰電池深放電較傷,建議 20% 前就充電。")
        if (a.avgStartLv in 0f..15f && a.chargeCount >= 3) tips.add("平均在 %.0f%% 才插電,偏低。提早到 20~30% 有助延壽。".format(a.avgStartLv))
        if (a.offRate > 1.5f) tips.add("待機耗電 %.1f %%/時偏高(正常多在 1 以下),請到「應用」「隱私」檢查背景常駐的 App。".format(a.offRate))
        val worst = deep.bandRates.withIndex().filter { it.value >= 0f }.maxByOrNull { it.value }
        if (worst != null && worst.index == 4 && worst.value > 2.5f * (a.rate.coerceAtLeast(0.1f))) tips.add("電量低於 20% 時耗電明顯變快(%.1f %%/時),電池在低電量區間壓降較大,是老化的常見徵兆。".format(worst.value))
        if (tips.isEmpty()) tips.add("目前的充電與用電習慣良好,繼續保持。")
        val tc = Ui.card(ctx)
        tc.addView(Ui.tv(ctx, "分析與建議", 15f, Theme.TEXT, true))
        tips.forEach { tc.addView(Ui.tv(ctx, "• $it", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0)) }
        body.addView(tc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))

        val hb = BarChartView(ctx).apply {
            title = "各時段平均耗電(每日)"; color = Theme.PURPLE; emptyText = "尚無放電資料"
            fmt = { "%.1f".format(it) }; maxFmt = { "%.1f%%".format(it) }
            set((0 until 24).map { "$it" }, a.hourDrain.toList())
        }
        chart(hb, 180)
    }

    private fun kpis(a: BatAnalysis) {
        val ratio = if (a.chargeMs > 0) a.fullPlugMs.toFloat() / a.chargeMs else 0f
        val runtime = MetricCard(ctx, "預估滿電續航", false).also { it.set(if (a.rate > 0) "%.1f 小時".format(100f / a.rate) else "—", "整體 ${r1(a.rate)} %/時", null) }
        val on = MetricCard(ctx, "亮屏耗電").also {
            it.set(if (a.onRate >= 0) "${r1(a.onRate)} %/時" else "—", if (a.onRate > 0) "亮屏約可用 %.1f 小時".format(100f / a.onRate) else "資料不足",
                if (a.onRate >= 0) (a.onRate / 25f).coerceIn(0f, 1f) else null, Theme.usage(a.onRate / 25f))
        }
        val off = MetricCard(ctx, "待機耗電(息屏)").also {
            it.set(if (a.offRate >= 0) "${r1(a.offRate)} %/時" else "—", if (a.offRate > 1.5f) "偏高,可能有 App 背景耗電" else if (a.offRate >= 0) "待機表現正常" else "資料不足",
                if (a.offRate >= 0) (a.offRate / 3f).coerceIn(0f, 1f) else null, Theme.usage(a.offRate / 3f))
        }
        val cs = MetricCard(ctx, "充電速度", false).also { it.set(if (a.chargeRate >= 0) "${r1(a.chargeRate)} %/時" else "—", "80% 以下:" + (if (a.chargeRate80 >= 0) "${r1(a.chargeRate80)} %/時" else "—"), null) }
        val cw = MetricCard(ctx, "平均充電功率", false).also { it.set(if (a.avgChargeW >= 0) "%.1f W".format(a.avgChargeW) else "—", "電流 × 電壓", null) }
        val eq = MetricCard(ctx, "等效完整循環", false).also { it.set("%.1f 次".format(a.eqCycles), "累計耗電 ${a.totalDrain}%", null) }
        val cc = MetricCard(ctx, "充電次數", false).also { it.set("${a.chargeCount} 次", "平均每日 %.1f 次".format(a.chargeCount / a.days), null) }
        val fp = MetricCard(ctx, "滿電後仍插電").also { it.set("%.0f%%".format(ratio * 100), "共 ${fmtDur(a.fullPlugMs)}", ratio, Theme.usage(ratio / 0.6f)) }
        Ui.grid(ctx, body, runtime, on, off, cs, cw, eq, cc, fp)
    }

    // ---------- 放電 ----------
    private fun discharge(d: D, a: BatAnalysis, deep: BatDeep) {
        val tot = (deep.onDrain + deep.offDrain).coerceAtLeast(1)
        val onShare = deep.onDrain.toFloat() / tot
        val sc = Ui.card(ctx)
        sc.addView(Ui.tv(ctx, "耗電來源:亮屏 vs 待機", 15f, Theme.TEXT, true))
        sc.addView(Ui.tv(ctx, "亮屏 ${(onShare * 100).toInt()}%(${deep.onDrain}% · ${fmtDur(deep.onMs)})", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 4))
        sc.addView(MeterView(ctx).apply { value = onShare; color = Theme.BLUE }, Ui.lp(ctx, Ui.MATCH, ctx.dp(8), 0, 8))
        sc.addView(Ui.tv(ctx, "待機 ${100 - (onShare * 100).toInt()}%(${deep.offDrain}% · ${fmtDur(deep.offMs)})", 12f, Theme.SUB))
        sc.addView(MeterView(ctx).apply { value = 1f - onShare; color = Theme.AMBER }, Ui.lp(ctx, Ui.MATCH, ctx.dp(8), 4, 0))
        body.addView(sc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val lbl = dayLabels(d)
        chart(BarChartView(ctx).apply {
            title = "每日耗電(放電總量)"; color = Theme.PURPLE; emptyText = "尚無資料"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f%%".format(it) }
            set(lbl, d.days.map { it.drain.toFloat() })
        }, 180)
        chart(ChartView(ctx).apply {
            title = "每日預估滿電續航"; unit = " 小時"; colorNormal = Theme.GREEN; longRange = true
            setData(d.days.filter { it.runtimeH > 0 }.map { ChartView.Pt(it.day + 12 * 3_600_000L, it.runtimeH.coerceAtMost(200f), false) })
        }, 170)
        chart(LineChartView(ctx).apply {
            title = "平均放電曲線(拔掉充電器後,${deep.dischargeSessions} 次)"; xUnit = "h"; yUnit = "%"; yMin = 0f; yMax = 100f; emptyText = "需要至少一次放電超過 2 小時"
            set(listOf(Series("電量", Theme.GREEN, FloatArray(deep.dischargeCurve.size) { deep.dischargeCurve[it].x }, FloatArray(deep.dischargeCurve.size) { deep.dischargeCurve[it].y })))
        }, 190)
        chart(BarChartView(ctx).apply {
            title = "分段耗電速率(每小時%)"; color = Theme.AMBER; emptyText = "資料不足"; fmt = { "%.1f".format(it) }; maxFmt = { "%.1f %%/時".format(it) }
            set(listOf("100-80", "80-60", "60-40", "40-20", "20-0"), deep.bandRates.map { if (it < 0f) 0f else it })
        }, 180)
        chart(BarChartView(ctx).apply {
            title = "放電功率分布(累計時間)"; color = Theme.CYAN; emptyText = "尚無電流資料"
            fmt = { fmtDur((it * 60_000L).toLong()) }; maxFmt = { fmtDur((it * 60_000L).toLong()) }
            set((0 until 12).map { if (it == 11) "5.5+" else "%.1f".format((it + 1) * 0.5f) }, deep.powerHist.toList())
        }, 180)
        body.addView(Ui.note(ctx, "放電曲線是把多次放電「從拔掉充電器起」的電量平均;分段耗電速率可看出低電量是否掉得特別快(老化徵兆)。功率分布的橫軸單位是 W。"))
    }

    // ---------- 充電 ----------
    private fun charge(d: D, a: BatAnalysis, deep: BatDeep) {
        val ratio = if (a.chargeMs > 0) a.fullPlugMs.toFloat() / a.chargeMs else 0f
        val c1 = MetricCard(ctx, "充電次數", false).also { it.set("${a.chargeCount} 次", "平均每日 %.1f 次".format(a.chargeCount / a.days), null) }
        val c2 = MetricCard(ctx, "平均充電功率", false).also { it.set(if (a.avgChargeW >= 0) "%.1f W".format(a.avgChargeW) else "—", "速度 ${r1(a.chargeRate)} %/時", null) }
        val c3 = MetricCard(ctx, "平均插電 → 拔電").also {
            it.set(if (a.avgStartLv >= 0) "%.0f%% → %.0f%%".format(a.avgStartLv, a.avgEndLv) else "—", "深度放電 ${a.deepCount} 次", if (a.avgStartLv >= 0) a.avgStartLv / 100f else null, Theme.good(a.avgStartLv / 100f))
        }
        val c4 = MetricCard(ctx, "滿電後仍插電").also { it.set("%.0f%%".format(ratio * 100), "共 ${fmtDur(a.fullPlugMs)}", ratio, Theme.usage(ratio / 0.6f)) }
        val c5 = MetricCard(ctx, "夜間充電", false).also {
            it.set("${deep.overnightCount} 次", if (deep.overnightCount > 0) "平均滿電停留 %.1f 小時".format(deep.overnightFullH / deep.overnightCount) else "沒有整夜充電", null)
        }
        val c6 = MetricCard(ctx, "充電溫度").also {
            it.set(if (a.avgChargeTemp > -50) "%.1f °C".format(a.avgChargeTemp) else "—", "最高 " + (if (a.maxChargeTemp > -50) "%.1f °C".format(a.maxChargeTemp) else "—"),
                if (a.maxChargeTemp > -50) (a.maxChargeTemp / 50f).coerceIn(0f, 1f) else null, Theme.usage(a.maxChargeTemp / 45f))
        }
        Ui.grid(ctx, body, c1, c2, c3, c4, c5, c6)

        fun ser(name: String, color: Int, p: List<Pt2>) = Series(name, color, FloatArray(p.size) { p[it].x }, FloatArray(p.size) { p[it].y })
        chart(LineChartView(ctx).apply {
            title = "平均充電曲線(${deep.chargeSessions} 次)"; xUnit = " 分"; yUnit = "%"; yMin = 0f; yMax = 100f; emptyText = "需要至少一次由低電量充電 20 分鐘以上"
            set(listOf(ser("電量", Theme.CYAN, deep.chargeLevelCurve)))
        }, 190)
        chart(LineChartView(ctx).apply {
            title = "充電功率變化(自插上充電器起)"; xUnit = " 分"; yUnit = "W"; emptyText = "尚無電流資料"
            set(listOf(ser("功率", Theme.AMBER, deep.chargePowerCurve)))
        }, 170)
        chart(LineChartView(ctx).apply {
            title = "充電功率 vs 電量(看降速點)"; xUnit = "%"; yUnit = "W"; emptyText = "尚無電流資料"
            set(listOf(ser("功率", Theme.PURPLE, deep.powerByLevel)))
        }, 170)
        val lv = (0 until 10).map { "${it * 10}-${it * 10 + 10}" }
        chart(BarChartView(ctx).apply {
            title = "插上充電器時的電量分布"; color = Theme.GREEN; emptyText = "尚無資料"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f 次".format(it) }
            set(lv, deep.plugHist.map { it.toFloat() })
        }, 170)
        chart(BarChartView(ctx).apply {
            title = "拔掉充電器時的電量分布"; color = Theme.BLUE; emptyText = "尚無資料"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f 次".format(it) }
            set(lv, deep.unplugHist.map { it.toFloat() })
        }, 170)
        chart(BarChartView(ctx).apply {
            title = "常在幾點開始充電"; color = Theme.CYAN; emptyText = "尚無資料"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f 次".format(it) }
            set((0 until 24).map { "$it" }, deep.chargeHourHist.map { it.toFloat() })
        }, 170)
        chart(LineChartView(ctx).apply {
            title = "每次充電的最高溫"; xUnit = " 次"; yUnit = "°C"; emptyText = "尚無資料"
            val m = deep.chargeMaxTemps
            set(listOf(Series("最高溫", Theme.RED, FloatArray(m.size) { it + 1f }, FloatArray(m.size) { m[it] })))
        }, 170)

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
                "放電 ${s.lv0}% → ${s.lv1}% · $dur" + (if (hrs > 0.2f) " · %.1f %%/時".format((s.lv0 - s.lv1) / hrs) else "") + " · 亮屏 ${fmtDur(s.screenMs)}"
            }
            sc.addView(Ui.tv(ctx, (if (s.charging) "⚡ " else "🔋 ") + fmtTime(s.start) + " → " + fmtTime(s.end, "HH:mm"), 13f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            sc.addView(Ui.tv(ctx, sub, 11f, Theme.SUB))
        }
        body.addView(sc)
    }

    // ---------- 健康 ----------
    private fun health(d: D, a: BatAnalysis, deep: BatDeep) {
        val s = d.last
        val hp = healthPct(s)
        val hc = Ui.card(ctx, 24)
        hc.addView(Ui.tv(ctx, "電池健康度", 12f, Theme.SUB))
        hc.addView(Ui.tv(ctx, hp?.let { "$it%" } ?: "未提供", 32f, Theme.good((hp ?: 80) / 100f), true))
        if (hp != null) hc.addView(MeterView(ctx).apply { value = hp / 100f; color = Theme.good(hp / 100f) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(8), 6, 6))
        val rows = ArrayList<Pair<String, String>>()
        if (s != null) {
            rows.add("設計容量" to if (s.designUah > 0) "${s.designUah / 1000} mAh" else "未知")
            rows.add("目前滿電容量" to if (s.fullUah > 0) "${s.fullUah / 1000} mAh" else "系統未提供")
            val est = a.capPts.lastOrNull()?.v
            rows.add("推估容量(近日)" to if (est != null) "%.0f mAh".format(est) else "資料不足")
            rows.add("系統回報健康度" to if (s.soh > 0) "${s.soh}%" else "不支援")
            rows.add("充電循環(系統)" to if (s.cycle >= 0) "${s.cycle} 次" else "不支援")
            rows.add("期間等效循環" to "%.1f 次".format(a.eqCycles))
        }
        val kv = KvCard(ctx, "容量與循環"); kv.set(rows)
        body.addView(hc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(kv, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        chart(ChartView(ctx).apply {
            title = "推估電池容量"; unit = " mAh"; colorNormal = Theme.GREEN; longRange = true; setData(a.capPts)
        }, 170)
        var cum = 0f
        val cumPts = d.days.map { cum += it.drain / 100f; ChartView.Pt(it.day + 12 * 3_600_000L, cum, false) }
        chart(ChartView(ctx).apply {
            title = "累計等效循環"; unit = " 次"; colorNormal = Theme.PURPLE; longRange = true; setData(cumPts)
        }, 170)
        fun ser(name: String, color: Int, p: List<Pt2>) = Series(name, color, FloatArray(p.size) { p[it].x }, FloatArray(p.size) { p[it].y })
        chart(LineChartView(ctx).apply {
            title = "放電電壓 vs 電量(老化時低電量電壓會更低)"; xUnit = "%"; yUnit = "V"; emptyText = "資料不足"
            yFmt = { "%.2f".format(it) }
            set(listOf(ser("電壓", Theme.BLUE, deep.voltByLevel)))
        }, 190)
        val td = d.days.withIndex().filter { it.value.avgTemp > -50f }
        chart(LineChartView(ctx).apply {
            title = "每日電池溫度"; xUnit = " 日"; yUnit = "°C"; emptyText = "資料不足"
            set(listOf(
                Series("均溫", Theme.AMBER, FloatArray(td.size) { td[it].index.toFloat() }, FloatArray(td.size) { td[it].value.avgTemp }),
                Series("最高", Theme.RED, FloatArray(td.size) { td[it].index.toFloat() }, FloatArray(td.size) { td[it].value.maxTemp })))
        }, 180)
        body.addView(Ui.note(ctx, "「推估容量」= 剩餘電荷 ÷ 電量%,會有雜訊,請看長期趨勢;若持續下滑代表老化。電壓曲線向下偏移同樣是老化徵兆。"))
    }

    // ---------- 熱圖 ----------
    private fun heat(deep: BatDeep) {
        val h1 = HeatmapView(ctx).apply { title = "耗電熱圖(%/小時,青色底線=充電中)"; color = Theme.AMBER; unit = "%"; set(deep.heatLabels, deep.heatDrain, deep.heatCharge) }
        chart(h1, deep.heatLabels.size * 14 + 56)
        val h2 = HeatmapView(ctx).apply { title = "亮屏熱圖(分鐘/小時)"; color = Theme.BLUE; unit = " 分"; set(deep.heatLabels, deep.heatScreen) }
        chart(h2, deep.heatLabels.size * 14 + 56)
        body.addView(Ui.note(ctx, "近 14 天,每格是一天中的一個小時。顏色越深越耗電 / 亮屏越久;按住格子可看數值。可一眼看出哪些時段最耗電、是否整夜充電。"))
    }
}
