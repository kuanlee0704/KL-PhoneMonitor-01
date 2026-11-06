package com.example.phonemonitor

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 趨勢 → 單日:以日為單位看當天的完整趨勢(可翻到過去任一天) */
class DayView(private val act: Activity) {
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val label = Ui.tv(act, "", 15f, Theme.TEXT, true).apply { gravity = Gravity.CENTER }
    private val ui = Handler(Looper.getMainLooper())
    private var day = AppUsage.dayStart(System.currentTimeMillis())
    private var lastLoad = 0L
    private val fmt = SimpleDateFormat("yyyy/MM/dd (E)", Locale.getDefault())
    private val DAY = AppUsage.DAY

    init {
        val nav = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun small(t: String, onClick: () -> Unit) = Ui.btn(act, t, Theme.CARD2, Theme.TEXT, onClick).apply { setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10)) }
        nav.addView(small("◀") { setDay(day - DAY) })
        label.setOnClickListener { pick() }
        nav.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        nav.addView(small("▶") { if (day < AppUsage.dayStart(System.currentTimeMillis())) setDay(day + DAY) })
        nav.addView(small("今天") { setDay(AppUsage.dayStart(System.currentTimeMillis())) }, Ui.lp(act, Ui.WRAP, Ui.WRAP).apply { marginStart = act.dp(6) })
        view.addView(nav, Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 10))
        view.addView(body)
    }

    private fun pick() {
        val c = Calendar.getInstance(); c.timeInMillis = day + 12 * 3_600_000L
        DatePickerDialog(act, { _, y, m, d ->
            val cc = Calendar.getInstance(); cc.set(y, m, d, 12, 0, 0)
            setDay(AppUsage.dayStart(cc.timeInMillis))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    fun setDay(d: Long) { day = d; load() }

    /** 檢視「今天」時每 30 秒自動更新 */
    fun tick() {
        if (day == AppUsage.dayStart(System.currentTimeMillis()) && System.currentTimeMillis() - lastLoad > 30_000L) load()
    }

    fun load() {
        lastLoad = System.currentTimeMillis()
        label.text = fmt.format(Date(day + 12 * 3_600_000L))
        val d0 = day
        Thread {
            val db = Db.get(act)
            val rows = db.between(d0, d0 + DAY)
            val prev = db.between(d0 - DAY, d0)
            val tr = if (rows.size >= 2) Stats.compute(rows, d0 + DAY - 1, 24) else null
            val ptr = if (prev.size >= 2) Stats.compute(prev, d0 - 1, 24) else null
            val agg = DayAgg.compute(rows, d0, d0 + DAY).firstOrNull()
            val scr = FloatArray(24)
            val tz = java.util.TimeZone.getDefault().getOffset(d0)
            for (k in 1 until rows.size) {
                val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
                if (dt in 1..(65 * 60_000L) && a.screenOn) scr[(((b.ts + tz) / 3_600_000L) % 24).toInt()] += dt / 60_000f
            }
            ui.post {
                if (d0 == day) try { render(rows, tr, ptr, agg, scr) } catch (e: Throwable) {
                    body.removeAllViews(); body.addView(Ui.note(act, "顯示錯誤:${e.javaClass.simpleName}: ${e.message}", Theme.RED))
                }
            }
        }.start()
    }

    private fun render(rows: List<Sample>, t: Trend?, p: Trend?, agg: DayStat?, scr: FloatArray) {
        body.removeAllViews()
        if (t == null) { body.addView(Ui.card(act).apply { addView(Ui.note(act, "這一天沒有足夠的紀錄。(App 需開啟監測才會記錄)")) }); return }
        val cov = maxOf(1L, t.coveredMs)
        val first = rows.first().level; val last = rows.last().level
        val c1 = MetricCard(act, "電量變化").also { it.set("$first% → $last%", "放電 -${t.drain}% · 充電 +${t.charged}%", last / 100f, Theme.good(last / 100f)) }
        val c2 = MetricCard(act, "平均耗電速率", false).also { it.set(if (t.drainPerHour >= 0) "%.1f %%/時".format(t.drainPerHour) else "—", if (t.drainPerHour > 0) "約可用 %.1f 小時".format(100f / t.drainPerHour) else "放電資料不足", null) }
        val c3 = MetricCard(act, "充電時間").also { it.set(fmtDur(t.chargeMs), "放電 ${fmtDur(t.dischargeMs)}", t.chargeMs.toFloat() / cov, Theme.CYAN) }
        val c4 = MetricCard(act, "亮屏時間").also { it.set(fmtDur(t.screenMs), "占統計時間 ${t.screenMs * 100 / cov}%", t.screenMs.toFloat() / cov, Theme.BLUE) }
        val c5 = MetricCard(act, "電池溫度").also { it.set("%.1f °C".format(t.tAvg), "最低 %.1f ~ 最高 %.1f".format(t.tMin, t.tMax), (t.tAvg / 60f).coerceIn(0f, 1f), Theme.usage(t.tMax / 50f)) }
        val c6 = MetricCard(act, "平均功率", false).also { it.set(if (t.pDis >= 0) "放電 %.2f W".format(t.pDis) else "—", if (t.pChg >= 0) "充電 %.2f W".format(t.pChg) else "沒有充電資料", null) }
        val net = agg?.net ?: 0L
        val c7 = MetricCard(act, "網路流量(本機取樣)", false).also {
            it.set(fmtBytes(net), if (agg != null && agg.mobile + agg.wifi > 0) "行動 ${fmtBytes(agg.mobile)} · Wi-Fi ${fmtBytes(agg.wifi)}" else "↓ ${fmtBytes(t.rx)} ↑ ${fmtBytes(t.tx)}", null)
        }
        val c8 = MetricCard(act, "記憶體平均使用").also { it.set("${t.memUsedAvgPct}%", "最低可用 ${fmtMb(t.memMin.toLong())}", t.memUsedAvgPct / 100f, Theme.usage(t.memUsedAvgPct / 100f)) }
        Ui.grid(act, body, c1, c2, c3, c4, c5, c6, c7, c8)

        if (p != null) {
            val dd = t.drain - p.drain; val ds = (t.screenMs - p.screenMs) / 60_000L
            body.addView(Ui.note(act, "與前一日比較:耗電 " + (if (dd >= 0) "+" else "") + "$dd%,亮屏 " + (if (ds >= 0) "+" else "") + "$ds 分鐘"))
        }

        val hours = (0 until 24).map { "$it" }
        body.addView(Ui.chartCard(act, BarChartView(act).apply {
            title = "每小時耗電"; color = Theme.PURPLE; emptyText = "沒有放電紀錄"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f%%".format(it) }; set(hours, t.barValues)
        }, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 4, 8))
        body.addView(Ui.chartCard(act, BarChartView(act).apply {
            title = "每小時亮屏(分鐘)"; color = Theme.BLUE; emptyText = "沒有亮屏紀錄"; fmt = { "%.0f".format(it) }; maxFmt = { "%.0f 分".format(it) }; set(hours, scr.toList())
        }, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8))

        fun line(title: String, unit: String, color: Int, fixed: Boolean, pts: List<ChartView.Pt>) {
            val cv = ChartView(act).apply {
                this.title = title; this.unit = unit; colorNormal = color; axisStart = day; axisEnd = day + DAY
                if (fixed) { fixedMin = 0f; fixedMax = 100f }
                setData(pts)
            }
            body.addView(Ui.chartCard(act, cv, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8))
        }
        line("電量", "%", Theme.GREEN, true, rows.filter { it.level >= 0 }.map { ChartView.Pt(it.ts, it.level.toFloat(), it.charging) })
        line("電池溫度", "°C", Theme.AMBER, false, rows.filter { it.tempC > -50f }.map { ChartView.Pt(it.ts, it.tempC, it.charging) })
        line("電流(mA)", " mA", Theme.PURPLE, false, rows.filter { it.currentMa != Int.MIN_VALUE }.map { ChartView.Pt(it.ts, it.currentMa.toFloat(), it.charging) })
        line("電壓", " V", Theme.BLUE, false, rows.filter { it.voltageMv > 0 }.map { ChartView.Pt(it.ts, it.voltageMv / 1000f, it.charging) })
        line("記憶體使用率", "%", Theme.PINK, true, rows.filter { it.memTotalMb > 0 }.map { ChartView.Pt(it.ts, (it.memTotalMb - it.memAvailMb) * 100f / it.memTotalMb, false) })
        body.addView(Ui.note(act, "圖表橫軸固定為 00:00 ~ 24:00;按住圖表可看該時間點數值。"))
    }
}

/** 趨勢 → 每日:過去 7 / 30 / 90 天,每天一筆彙整 */
class DailyView(private val act: Activity, private val onPick: (Long) -> Unit) {
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val ui = Handler(Looper.getMainLooper())
    private val ranges = intArrayOf(7, 30, 90)
    private var ri = 1
    private var lastLoad = 0L
    private val chips = Chips(act, listOf("7 天", "30 天", "90 天"), 1) { ri = it; load() }

    init { view.addView(chips.view, Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 10)); view.addView(body) }

    fun tick() { if (System.currentTimeMillis() - lastLoad > 60_000L) load() }

    fun load() {
        lastLoad = System.currentTimeMillis()
        val n = ranges[ri]
        Thread {
            val now = System.currentTimeMillis(); val today = AppUsage.dayStart(now)
            val rows = Db.get(act).since(today - (n - 1) * AppUsage.DAY)
            val days = DayAgg.compute(rows, today - (n - 1) * AppUsage.DAY, today + AppUsage.DAY)
            ui.post {
                try { render(days) } catch (e: Throwable) {
                    body.removeAllViews(); body.addView(Ui.note(act, "顯示錯誤:${e.javaClass.simpleName}: ${e.message}", Theme.RED))
                }
            }
        }.start()
    }

    private fun render(days: List<DayStat>) {
        body.removeAllViews()
        val with = days.filter { it.samples >= 2 }
        if (with.isEmpty()) { body.addView(Ui.card(act).apply { addView(Ui.note(act, "這段期間沒有紀錄。")) }); return }
        val sdf = SimpleDateFormat("M/d", Locale.getDefault())
        val lbl = days.map { sdf.format(Date(it.day + 12 * 3_600_000L)) }
        val mid = { d: DayStat -> d.day + 12 * 3_600_000L }

        val avgDrain = with.map { it.drain }.average()
        val avgScr = with.map { it.screenMs }.average().toLong()
        val c1 = MetricCard(act, "平均每日耗電", false).also { it.set("%.0f%%".format(avgDrain), "共 ${with.size} 天有紀錄", null) }
        val c2 = MetricCard(act, "平均每日亮屏", false).also { it.set(fmtDur(avgScr), "最長 ${fmtDur(with.maxOf { it.screenMs })}", null) }
        val rt = with.filter { it.runtimeH > 0 }.map { it.runtimeH.coerceAtMost(200f) }
        val c3 = MetricCard(act, "平均預估續航", false).also { it.set(if (rt.isNotEmpty()) "%.1f 小時".format(rt.average()) else "—", "滿電到沒電", null) }
        val c4 = MetricCard(act, "平均每日流量", false).also { it.set(fmtBytes(with.map { it.net }.average().toLong()), "累計 ${fmtBytes(with.sumOf { it.net })}", null) }
        Ui.grid(act, body, c1, c2, c3, c4)

        fun bar(title: String, color: Int, vals: List<Float>, f: (Float) -> String) {
            body.addView(Ui.chartCard(act, BarChartView(act).apply {
                this.title = title; this.color = color; emptyText = "尚無資料"; fmt = f; maxFmt = f; set(lbl, vals)
            }, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8))
        }
        bar("每日耗電(%)", Theme.PURPLE, days.map { it.drain.toFloat() }) { "%.0f".format(it) }
        bar("每日亮屏時間", Theme.BLUE, days.map { it.screenMs / 60_000f }) { if (it >= 60f) "%.1f 時".format(it / 60f) else "%.0f 分".format(it) }
        bar("每日充電次數", Theme.CYAN, days.map { it.chargeCount.toFloat() }) { "%.0f".format(it) }
        bar("每日網路流量", Theme.AMBER, days.map { it.net.toFloat() }) { fmtBytes(it.toLong()) }

        body.addView(Ui.chartCard(act, ChartView(act).apply {
            title = "每日預估滿電續航"; unit = " 小時"; colorNormal = Theme.GREEN; longRange = true
            setData(days.filter { it.runtimeH > 0 }.map { ChartView.Pt(mid(it), it.runtimeH.coerceAtMost(200f), false) })
        }, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8))
        val tp = days.filter { it.avgTemp > -50f }
        body.addView(Ui.chartCard(act, LineChartView(act).apply {
            title = "每日電池溫度"; yUnit = "°C"; emptyText = "尚無資料"
            val idx = days.withIndex().filter { it.value.avgTemp > -50f }
            set(listOf(
                Series("均溫", Theme.AMBER, FloatArray(idx.size) { idx[it].index.toFloat() }, FloatArray(idx.size) { idx[it].value.avgTemp }),
                Series("最高", Theme.RED, FloatArray(idx.size) { idx[it].index.toFloat() }, FloatArray(idx.size) { idx[it].value.maxTemp })))
        }, 170), Ui.lp(act, Ui.MATCH, Ui.WRAP, 0, 8))
        if (tp.isEmpty()) Unit

        body.addView(Ui.section(act, "每日明細(點選可看當天趨勢)"))
        val card = Ui.card(act)
        for (d in days.reversed().take(45)) {
            if (d.samples < 2) continue
            val r = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, act.dp(8), 0, act.dp(8)) }
            r.addView(Ui.tv(act, SimpleDateFormat("M/d (E)", Locale.getDefault()).format(Date(d.day + 12 * 3_600_000L)) + "   ${d.levelStart}% → ${d.levelEnd}%", 13f, Theme.TEXT, true))
            r.addView(Ui.tv(act, "耗電 ${d.drain}% · 亮屏 ${fmtDur(d.screenMs)} · 充電 ${d.chargeCount} 次 · " +
                (if (d.avgTemp > -50f) "均溫 %.1f°C · ".format(d.avgTemp) else "") + "流量 ${fmtBytes(d.net)}", 11f, Theme.SUB))
            r.setOnClickListener { onPick(d.day) }
            card.addView(r)
        }
        body.addView(card)
    }
}
