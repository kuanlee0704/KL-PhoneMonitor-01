package com.example.phonemonitor

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val reqExport = 100
    private var tab = 0

    private lateinit var pages: List<ScrollView>
    private lateinit var navs: List<TextView>

    // 即時
    private lateinit var pill: TextView
    private lateinit var ring: RingView
    private lateinit var statusLine: TextView
    private lateinit var hVolt: TextView
    private lateinit var hCur: TextView
    private lateinit var hPow: TextView
    private lateinit var cHealth: MetricCard
    private lateinit var cRemain: MetricCard
    private lateinit var cTemp: MetricCard
    private lateinit var cCycle: MetricCard
    private lateinit var cThermal: MetricCard
    private lateinit var cPower: MetricCard
    private lateinit var cMem: MetricCard
    private lateinit var cStor: MetricCard
    private lateinit var cNet: MetricCard
    private lateinit var cUp: MetricCard

    // 趨勢
    private val rangeHours = longArrayOf(1, 6, 24, 168)
    private val rangeNames = listOf("1 小時", "6 小時", "24 小時", "7 天")
    private var rangeIdx = 2
    private lateinit var rangeBtns: List<TextView>
    private lateinit var emptyNote: TextView
    private lateinit var tDrain: MetricCard
    private lateinit var tLevel: MetricCard
    private lateinit var tTime: MetricCard
    private lateinit var tScreen: MetricCard
    private lateinit var tTemp: MetricCard
    private lateinit var tPower: MetricCard
    private lateinit var tNet: MetricCard
    private lateinit var tMem: MetricCard
    private lateinit var barChart: BarChartView
    private lateinit var chLevel: ChartView
    private lateinit var chTemp: ChartView
    private lateinit var chCur: ChartView
    private lateinit var chVolt: ChartView
    private lateinit var chMem: ChartView

    // 設定
    private val intervals = intArrayOf(60, 300, 900, 1800)
    private lateinit var sToggle: TextView
    private lateinit var sChips: List<TextView>
    private lateinit var sInfo: TextView
    private lateinit var sDiag: TextView

    private val loop = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 5000) }
    }

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); ui.post(loop) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(loop) }

    // ---------- UI 小工具 ----------
    private fun tv(t: String, sp: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun card(radius: Int = 20) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, dp(radius).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun btn(text: String, bg: Int, fg: Int = Color.WHITE, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD); background = Theme.rounded(bg, dp(14).toFloat())
        setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { onClick() }
    }

    private fun section(t: String) = tv(t, 13f, Theme.SUB, true).apply { setPadding(dp(4), dp(12), 0, dp(6)) }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(inner) }
        return Pair(sv, inner)
    }

    private fun grid(parent: LinearLayout, vararg cards: View) {
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val p = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    if (j == 0) marginEnd = dp(4) else marginStart = dp(4)
                }
                row.addView(cards.getOrNull(i + j) ?: View(this), p)
            }
            parent.addView(row, lp(MATCH, WRAP, 0, 8))
            i += 2
        }
    }

    private fun chartCard(v: View, h: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, dp(18).toFloat())
        setPadding(dp(8), dp(8), dp(8), dp(6))
        addView(v, LinearLayout.LayoutParams(MATCH, dp(h)))
    }

    // ---------- 組畫面 ----------
    private fun buildUi() {
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.CARD
        val (p0, c0) = page(); val (p1, c1) = page(); val (p2, c2) = page()
        buildDashboard(c0); buildTrends(c1); buildSettings(c2)
        pages = listOf(p0, p1, p2)
        val frame = FrameLayout(this)
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Theme.CARD) }
        navs = listOf("📱 即時", "📈 趨勢", "⚙️ 設定").mapIndexed { i, t ->
            tv(t, 13f, Theme.SUB, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); setOnClickListener { showTab(i) }
            }
        }
        navs.forEach { nav.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        showTab(0)
    }

    private fun showTab(i: Int) {
        tab = i
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        navs.forEachIndexed { k, n -> n.setTextColor(if (k == i) Theme.TEXT else Theme.SUB) }
        refresh()
    }

    private fun buildDashboard(c: LinearLayout) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("手機監測", 24f, Theme.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        pill = tv("", 12f, Theme.TEXT, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { toggle() }
        }
        head.addView(pill)
        c.addView(head, lp(MATCH, WRAP, 0, 12))

        val hero = card(24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        ring = RingView(this)
        hero.addView(ring, LinearLayout.LayoutParams(dp(200), dp(200)))
        statusLine = tv("", 14f, Theme.SUB).apply { gravity = Gravity.CENTER }
        hero.addView(statusLine, lp(MATCH, WRAP, 6, 14))
        val mini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ministat(label: String): TextView {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            box.addView(tv(label, 11f, Theme.SUB))
            val v = tv("—", 16f, Theme.TEXT, true); box.addView(v)
            mini.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f)); return v
        }
        hVolt = ministat("電壓"); hCur = ministat("電流"); hPow = ministat("功率")
        hero.addView(mini, lp(MATCH, WRAP))
        c.addView(hero, lp(MATCH, WRAP, 0, 6))

        c.addView(section("電池"))
        cHealth = MetricCard(this, "電池健康度(完整度)")
        cRemain = MetricCard(this, "剩餘電量")
        cTemp = MetricCard(this, "電池溫度")
        cPower = MetricCard(this, "電流 / 功率")
        cCycle = MetricCard(this, "充電循環")
        cThermal = MetricCard(this, "系統熱狀態", false)
        grid(c, cHealth, cRemain, cTemp, cPower, cCycle, cThermal)

        c.addView(section("系統"))
        cMem = MetricCard(this, "記憶體使用")
        cStor = MetricCard(this, "儲存空間使用")
        cNet = MetricCard(this, "網路", false)
        cUp = MetricCard(this, "裝置狀態", false)
        grid(c, cMem, cStor, cNet, cUp)
    }

    private fun buildTrends(c: LinearLayout) {
        c.addView(tv("監測趨勢", 24f, Theme.TEXT, true), lp(MATCH, WRAP, 0, 12))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rounded(Theme.CARD, dp(14).toFloat()); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        rangeBtns = rangeNames.mapIndexed { i, n ->
            tv(n, 13f, Theme.SUB, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { rangeIdx = i; refresh() }
            }
        }
        rangeBtns.forEach { seg.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        c.addView(seg, lp(MATCH, WRAP, 0, 10))

        emptyNote = tv("尚無足夠資料。請到「設定」開始監測,累積幾筆後就會出現趨勢。", 13f, Theme.AMBER).apply {
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        c.addView(emptyNote)

        tDrain = MetricCard(this, "平均耗電速率", false)
        tLevel = MetricCard(this, "電量變化")
        tTime = MetricCard(this, "充電時間")
        tScreen = MetricCard(this, "亮屏時間")
        tTemp = MetricCard(this, "平均溫度")
        tPower = MetricCard(this, "平均放電功率", false)
        tNet = MetricCard(this, "網路用量", false)
        tMem = MetricCard(this, "記憶體平均使用")
        grid(c, tDrain, tLevel, tTime, tScreen, tTemp, tPower, tNet, tMem)

        barChart = BarChartView(this)
        c.addView(chartCard(barChart, 190), lp(MATCH, WRAP, 4, 8))

        chLevel = ChartView(this).apply { title = "電量"; unit = "%"; fixedMin = 0f; fixedMax = 100f; colorNormal = Theme.GREEN; colorHi = Theme.CYAN }
        chTemp = ChartView(this).apply { title = "電池溫度"; unit = "°C"; colorNormal = Theme.AMBER }
        chCur = ChartView(this).apply { title = "電流(充電/放電方向依機型)"; unit = "mA"; colorNormal = Theme.PURPLE }
        chVolt = ChartView(this).apply { title = "電壓"; unit = "V"; colorNormal = Theme.BLUE }
        chMem = ChartView(this).apply { title = "記憶體使用率"; unit = "%"; fixedMin = 0f; fixedMax = 100f; colorNormal = Theme.PINK }
        listOf(chLevel, chTemp, chCur, chVolt, chMem).forEach { c.addView(chartCard(it, 170), lp(MATCH, WRAP, 0, 8)) }
        c.addView(tv("小提示:手指按住圖表可查看該時間點的數值(綠/青色 = 充電中)", 11f, Theme.SUB))
    }

    private fun buildSettings(c: LinearLayout) {
        c.addView(tv("設定", 24f, Theme.TEXT, true), lp(MATCH, WRAP, 0, 12))

        val a = card()
        a.addView(tv("背景監測", 16f, Theme.TEXT, true))
        a.addView(tv("在背景定時記錄電池與系統資料,通知欄會顯示最新數值。重新開機或更新 App 後會自動恢復。", 12f, Theme.SUB), lp(MATCH, WRAP, 4, 12))
        sToggle = btn("", Theme.GREEN, Color.parseColor("#06251A")) { toggle() }
        a.addView(sToggle, lp(MATCH, WRAP))
        c.addView(a, lp(MATCH, WRAP, 0, 10))

        val b = card()
        b.addView(tv("取樣間隔", 16f, Theme.TEXT, true))
        b.addView(tv("間隔越短資料越細,但耗電略增。建議 5 分鐘。", 12f, Theme.SUB), lp(MATCH, WRAP, 4, 10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sChips = intervals.map { sec ->
            tv("${sec / 60} 分", 14f, Theme.SUB, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { setInterval(sec) }
            }
        }
        sChips.forEachIndexed { i, ch ->
            row.addView(ch, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        b.addView(row)
        c.addView(b, lp(MATCH, WRAP, 0, 10))

        val d = card()
        d.addView(tv("資料", 16f, Theme.TEXT, true))
        sInfo = tv("", 12f, Theme.SUB)
        d.addView(sInfo, lp(MATCH, WRAP, 4, 12))
        d.addView(btn("匯出 CSV", Theme.BLUE, Color.parseColor("#06182B")) { export() }, lp(MATCH, WRAP, 0, 8))
        d.addView(btn("允許背景執行(避免被系統殺掉)", Theme.CARD2, Theme.TEXT) { ignoreBatteryOpt() }, lp(MATCH, WRAP, 0, 8))
        d.addView(btn("清除所有資料", Theme.CARD2, Theme.RED) {
            AlertDialog.Builder(this).setTitle("清除所有資料?").setMessage("所有歷史紀錄會被刪除,無法復原。")
                .setPositiveButton("清除") { _, _ -> Db.get(this).clear(); refresh() }
                .setNegativeButton("取消", null).show()
        }, lp(MATCH, WRAP))
        c.addView(d, lp(MATCH, WRAP, 0, 10))

        val g = card()
        g.addView(tv("診斷", 16f, Theme.TEXT, true))
        sDiag = tv("", 12f, Theme.SUB)
        g.addView(sDiag, lp(MATCH, WRAP, 4, 12))
        g.addView(btn("立即記錄一次(測試)", Theme.CARD2, Theme.TEXT) { testRecord() }, lp(MATCH, WRAP))
        c.addView(g, lp(MATCH, WRAP, 0, 10))
        c.addView(tv("此 App 沒有網路權限,所有資料只存在你的手機。", 11f, Theme.SUB))
    }

    // ---------- 動作 ----------
    private fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        val i = Intent(this, MonitorService::class.java)
        if (on) {
            try { startForegroundService(i) } catch (e: Exception) {
                Prefs.setErr(this, "startService: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(this, "背景服務啟動失敗:${e.message}", Toast.LENGTH_LONG).show()
            }
            try { SampleJob.schedule(this) } catch (_: Exception) {}
            Thread { Recorder.record(this); runOnUiThread { refresh() } }.start()   // 立刻記一筆
        } else {
            stopService(i)
            try { SampleJob.cancel(this) } catch (_: Exception) {}
        }
        refresh()
    }

    private fun testRecord() {
        Thread {
            val s = Recorder.record(this)
            runOnUiThread {
                Toast.makeText(this, if (s != null) "記錄成功:電量 ${s.level}%、${"%.1f".format(s.tempC)}°C" else "記錄失敗,請看診斷區的錯誤訊息", Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = try {
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100)
            .any { it.service.className == MonitorService::class.java.name }
    } catch (e: Exception) { false }

    private fun setInterval(sec: Int) {
        Prefs.setInterval(this, sec)
        if (Prefs.enabled(this)) {
            val i = Intent(this, MonitorService::class.java)
            stopService(i); startForegroundService(i)
        }
        refresh()
    }

    private fun ignoreBatteryOpt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已允許背景執行", Toast.LENGTH_SHORT).show(); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun export() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "phone_monitor_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv")
        }
        startActivityForResult(i, reqExport)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != reqExport || res != RESULT_OK) return
        Thread {
            try {
                val rows = Db.get(this).since(0)
                val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write("\uFEFF時間,電量%,狀態,充電方式,健康,溫度C,電壓mV,電流mA,電荷計數uAh,滿電容量uAh,設計容量uAh,循環次數,SOH%,可用記憶體MB,總記憶體MB,可用儲存MB,總儲存MB,下載累計B,上傳累計B,網路,熱狀態,螢幕亮,省電模式\n")
                    for (s in rows) {
                        w.write(listOf(
                            f.format(Date(s.ts)), s.level, statusName(s.status), plugName(s.plugged), healthName(s.health),
                            s.tempC, s.voltageMv, if (s.currentMa == Int.MIN_VALUE) "" else s.currentMa,
                            s.chargeUah, s.fullUah, s.designUah, s.cycle, s.soh, s.memAvailMb, s.memTotalMb,
                            s.storFreeMb, s.storTotalMb, s.rxBytes, s.txBytes, s.net, s.thermal, s.screenOn, s.powerSave
                        ).joinToString(",") + "\n")
                    }
                }
                runOnUiThread { Toast.makeText(this, "已匯出 ${rows.size} 筆", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "匯出失敗:${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------- 文字對照 ----------
    private fun statusName(s: Int) = when (s) { 2 -> "充電中"; 3 -> "放電中"; 4 -> "未充電"; 5 -> "已充滿"; else -> "未知" }
    private fun plugName(p: Int) = when {
        p and 4 != 0 -> "無線"; p and 1 != 0 -> "AC"; p and 2 != 0 -> "USB"; p and 8 != 0 -> "Dock"; else -> "未接電"
    }
    private fun healthName(h: Int) = when (h) {
        2 -> "良好"; 3 -> "過熱"; 4 -> "損壞"; 5 -> "過電壓"; 6 -> "未知故障"; 7 -> "過冷"; else -> "未知"
    }
    private fun thermalName(t: Int) = when (t) {
        0 -> "正常"; 1 -> "輕微發熱"; 2 -> "中度發熱"; 3 -> "嚴重發熱"; 4 -> "危急"; 5 -> "緊急"; 6 -> "即將關機"; else -> "不支援"
    }

    /** 電池健康度:優先實測容量,其次系統回報,最後推估 */
    private fun healthInfo(s: Sample): Pair<Int?, String> {
        if (s.fullUah > 0 && s.designUah > 0)
            return Pair(minOf(100, s.fullUah * 100 / s.designUah), "實測 ${s.fullUah / 1000} / ${s.designUah / 1000} mAh")
        if (s.soh > 0) return Pair(minOf(100, s.soh), "系統回報(Android 14+)")
        if (s.designUah > 0 && s.chargeUah > 0 && s.level >= 30) {
            val est = s.chargeUah * 100L / s.level
            return Pair(minOf(100L, est * 100 / s.designUah).toInt(), "推估 ${est / 1000} / ${s.designUah / 1000} mAh")
        }
        return Pair(null, "此機型未提供容量資料")
    }

    private fun remainingText(s: Sample, rate: Float): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (s.charging) {
            if (s.status == 5 || s.level >= 100) return "已充滿"
            if (Build.VERSION.SDK_INT >= 28) {
                val ms = bm.computeChargeTimeRemaining()
                if (ms > 0) return "預計 ${fmtDur(ms)} 後充滿"
            }
            return "充電中"
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val dur = (getSystemService(Context.POWER_SERVICE) as PowerManager).batteryDischargePrediction
            if (dur != null) return "預估還可使用 ${fmtDur(dur.toMillis())}"
        }
        if (rate > 0.3f && s.level > 0) return "依近期用量,約可使用 ${fmtDur((s.level / rate * 3_600_000f).toLong())}"
        if (s.currentMa != Int.MIN_VALUE && s.currentMa != 0 && s.chargeUah > 0)
            return "依目前電流,約可使用 ${fmtDur((s.chargeUah / 1000f / Math.abs(s.currentMa) * 3_600_000f).toLong())}"
        return "放電中"
    }

    // ---------- 更新資料 ----------
    private fun refresh() {
        val on = Prefs.enabled(this)
        pill.text = if (on) "● 監測中" else "○ 未啟動"
        pill.setTextColor(if (on) Color.parseColor("#06251A") else Theme.SUB)
        pill.background = Theme.rounded(if (on) Theme.GREEN else Theme.CARD2, dp(20).toFloat())
        sToggle.text = if (on) "停止監測" else "開始監測"
        sToggle.background = Theme.rounded(if (on) Theme.RED else Theme.GREEN, dp(14).toFloat())
        rangeBtns.forEachIndexed { i, b ->
            b.setTextColor(if (i == rangeIdx) Color.parseColor("#06182B") else Theme.SUB)
            b.background = if (i == rangeIdx) Theme.rounded(Theme.BLUE, dp(10).toFloat()) else null
        }
        sChips.forEachIndexed { i, ch ->
            val sel = intervals[i] == Prefs.interval(this)
            ch.setTextColor(if (sel) Color.parseColor("#06182B") else Theme.SUB)
            ch.background = Theme.rounded(if (sel) Theme.BLUE else Theme.CARD2, dp(12).toFloat())
        }

        var liveErr = ""
        val live = try { Collector.read(this) } catch (e: Throwable) { liveErr = "${e.javaClass.simpleName}: ${e.message}"; null }
        val idx = rangeIdx; val t = tab
        Thread {
            val db = Db.get(this); val now = System.currentTimeMillis()
            // 備援:監測開啟、但背景服務超過 90 秒沒寫入(可能被系統砍掉),就由畫面補記一筆
            if (Prefs.enabled(this) && now - db.lastTs() > 90_000L) Recorder.record(this)
            val recent = db.since(now - 3 * 3600_000L).filter { !it.charging && it.level >= 0 }
            var rate = -1f
            if (recent.size >= 2 && recent.last().ts - recent.first().ts >= 15 * 60_000L) {
                val hrs = (recent.last().ts - recent.first().ts) / 3_600_000f
                rate = (recent.first().level - recent.last().level) / hrs
                if (rate <= 0f) rate = -1f
            }
            val total = db.count()
            val rows = if (t == 1) db.since(now - rangeHours[idx] * 3_600_000L) else null
            val tr = rows?.let { Stats.compute(it, now, rangeHours[idx]) }
            runOnUiThread {
                if (live != null) {
                    try { renderDashboard(live, rate) } catch (e: Throwable) {
                        statusLine.text = "顯示錯誤:${e.javaClass.simpleName}: ${e.message}"
                    }
                } else statusLine.text = "即時讀取失敗:$liveErr"
                val lastOk = Prefs.lastOk(this)
                sDiag.text = "手機:${Build.MANUFACTURER} ${Build.MODEL}(Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})\n" +
                    "背景服務:${if (serviceRunning()) "執行中" else "未執行"}\n" +
                    "最近成功記錄:${if (lastOk > 0) SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date(lastOk)) else "從未"}\n" +
                    "最近錯誤:${Prefs.lastErr(this).ifEmpty { "無" }}"
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                sInfo.text = "已記錄 $total 筆(保留 30 天)\n版本 $ver\n背景執行:${if (pm.isIgnoringBatteryOptimizations(packageName)) "已允許" else "尚未允許"}"
                if (rows != null && tr != null) renderTrends(rows, tr)
            }
        }.start()
    }

    private fun renderDashboard(s: Sample, rate: Float) {
        val ringColor = if (s.charging) Theme.CYAN else Theme.good(s.level / 100f)
        ring.set(s.level.toFloat(), "${s.level}%", "${statusName(s.status)} · ${plugName(s.plugged)}", ringColor)
        statusLine.text = remainingText(s, rate)
        hVolt.text = "%.2f V".format(s.voltageMv / 1000f)
        hCur.text = if (s.currentMa == Int.MIN_VALUE) "—" else "${s.currentMa} mA"
        hPow.text = if (s.currentMa == Int.MIN_VALUE || s.voltageMv <= 0) "—"
        else "%.2f W".format(Math.abs(s.currentMa.toLong() * s.voltageMv) / 1_000_000.0)

        val (hp, hsub) = healthInfo(s)
        cHealth.set(hp?.let { "$it%" } ?: "不支援", "${healthName(s.health)} · $hsub", hp?.let { it / 100f }, Theme.good((hp ?: 100) / 100f))

        if (s.chargeUah > 0) {
            val full = when {
                s.fullUah > 0 -> s.fullUah.toLong()
                s.level >= 20 -> s.chargeUah * 100L / s.level
                else -> s.designUah.toLong()
            }
            cRemain.set("${s.chargeUah / 1000} mAh", "${s.level}%" + if (full > 0) " · 滿電約 ${full / 1000} mAh" else "", s.level / 100f, Theme.good(s.level / 100f))
        } else cRemain.set("${s.level}%", "此機型未提供 mAh 資料", s.level / 100f, Theme.good(s.level / 100f))

        cTemp.set("%.1f °C".format(s.tempC), if (s.tempC >= 40f) "偏高,建議降溫" else if (s.tempC <= 5f) "偏低" else "溫度正常",
            (s.tempC / 60f).coerceIn(0f, 1f), Theme.usage(s.tempC / 50f))

        val ma = s.currentMa
        cPower.set(if (ma == Int.MIN_VALUE) "—" else "${Math.abs(ma)} mA",
            if (ma == Int.MIN_VALUE) "不支援" else (if (s.charging) "充電輸入" else "放電消耗") + " · " + hPow.text,
            if (ma == Int.MIN_VALUE) null else (Math.abs(ma) / 3000f).coerceIn(0f, 1f), if (s.charging) Theme.CYAN else Theme.AMBER)

        cCycle.set(if (s.cycle >= 0) "${s.cycle} 次" else "不支援", "一般約 800 次後容量明顯下降",
            if (s.cycle >= 0) (s.cycle / 800f).coerceIn(0f, 1f) else null, Theme.usage(s.cycle / 800f))

        cThermal.set(thermalName(s.thermal), "螢幕${if (s.screenOn) "亮" else "熄"} · 省電模式${if (s.powerSave) "開" else "關"}", null)

        val memUsed = s.memTotalMb - s.memAvailMb
        val mp = if (s.memTotalMb > 0) memUsed.toFloat() / s.memTotalMb else 0f
        cMem.set("${(mp * 100).toInt()}%", "可用 ${fmtMb(s.memAvailMb.toLong())} / ${fmtMb(s.memTotalMb.toLong())}", mp, Theme.usage(mp))

        val su = s.storTotalMb - s.storFreeMb
        val sp = if (s.storTotalMb > 0) su.toFloat() / s.storTotalMb else 0f
        cStor.set("${(sp * 100).toInt()}%", "剩餘 ${fmtMb(s.storFreeMb)} / ${fmtMb(s.storTotalMb)}", sp, Theme.usage(sp))

        cNet.set(s.net, "↓ ${fmtBytes(s.rxBytes)}  ↑ ${fmtBytes(s.txBytes)}(開機後)", null)
        cUp.set("${SystemClock.elapsedRealtime() / 3_600_000L} 小時", "已開機時間", null)
    }

    private fun renderTrends(rows: List<Sample>, t: Trend) {
        emptyNote.visibility = if (rows.size < 2) View.VISIBLE else View.GONE
        tDrain.set(if (t.drainPerHour >= 0) "%.1f %%/時".format(t.drainPerHour) else "—", "放電期間共耗 ${t.drain}%", null)
        if (rows.isNotEmpty()) {
            val a = rows.first().level; val z = rows.last().level
            tLevel.set("$a% → $z%", "放電 -${t.drain}% · 充電 +${t.charged}%", z / 100f, Theme.good(z / 100f))
        } else tLevel.set("—", "", null)
        val cov = maxOf(1L, t.coveredMs)
        tTime.set(fmtDur(t.chargeMs), "放電 ${fmtDur(t.dischargeMs)}", t.chargeMs.toFloat() / cov, Theme.CYAN)
        tScreen.set(fmtDur(t.screenMs), "占統計時間 ${t.screenMs * 100 / cov}%", t.screenMs.toFloat() / cov, Theme.BLUE)
        tTemp.set("%.1f °C".format(t.tAvg), "最低 %.1f ~ 最高 %.1f".format(t.tMin, t.tMax), (t.tAvg / 60f).coerceIn(0f, 1f), Theme.usage(t.tMax / 50f))
        tPower.set(if (t.pDis >= 0) "%.2f W".format(t.pDis) else "—", if (t.pChg >= 0) "充電平均 %.2f W".format(t.pChg) else "尚無充電資料", null)
        tNet.set(fmtBytes(t.rx + t.tx), "↓ ${fmtBytes(t.rx)}  ↑ ${fmtBytes(t.tx)}", null)
        tMem.set("${t.memUsedAvgPct}%", "最低可用 ${fmtMb(t.memMin.toLong())}", t.memUsedAvgPct / 100f, Theme.usage(t.memUsedAvgPct / 100f))

        barChart.title = t.barTitle; barChart.set(t.barLabels, t.barValues)
        val long = rangeHours[rangeIdx] > 24
        listOf(chLevel, chTemp, chCur, chVolt, chMem).forEach { it.longRange = long }
        chLevel.setData(rows.filter { it.level >= 0 }.map { ChartView.Pt(it.ts, it.level.toFloat(), it.charging) })
        chTemp.setData(rows.filter { it.tempC > -50f }.map { ChartView.Pt(it.ts, it.tempC, it.charging) })
        chCur.setData(rows.filter { it.currentMa != Int.MIN_VALUE }.map { ChartView.Pt(it.ts, it.currentMa.toFloat(), it.charging) })
        chVolt.setData(rows.filter { it.voltageMv > 0 }.map { ChartView.Pt(it.ts, it.voltageMv / 1000f, it.charging) })
        chMem.setData(rows.filter { it.memTotalMb > 0 }.map { ChartView.Pt(it.ts, (it.memTotalMb - it.memAvailMb) * 100f / it.memTotalMb, false) })
    }
}
