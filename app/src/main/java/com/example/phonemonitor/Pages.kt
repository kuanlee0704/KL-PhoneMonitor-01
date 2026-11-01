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

    private var loadedAt = 0L
    /** 切換分頁時 30 秒內不重複載入;force=true(回到 App)一律重載 */
    fun show(force: Boolean) {
        val n = System.currentTimeMillis()
        if (force || n - loadedAt > 30_000L) { loadedAt = n; load() }
    }

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
