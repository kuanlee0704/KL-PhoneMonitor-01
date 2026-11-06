package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtRate(bps: Float): String = when {
    bps < 1024f -> "%.0f B/s".format(bps)
    bps < 1_048_576f -> "%.1f KB/s".format(bps / 1024f)
    else -> "%.2f MB/s".format(bps / 1_048_576f)
}

/**
 * 網路:概覽 / 即時 / 各 App / 封鎖
 * 數據來源:
 *  - 總量與 App 排行:系統「流量統計」(與系統設定的數據用量一致);
 *  - 今天的每小時圖與即時速度:本機讀取網路流量計數(取樣期間內較準);
 *  - 封包層級:只有被「網路封鎖」的 App 會經過本機 VPN,可記錄其連線嘗試。
 */
class NetPage(a: Activity) : BasePage(a, "網路") {
    private var sub = 0
    private var pi = 1       // 0 今天 / 1 7天 / 2 30天 / 3 本月
    private var ti = 0       // 0 行動網路 / 1 Wi-Fi
    private var expand = false
    private val subChips = Chips(a, listOf("概覽", "即時", "各 App", "封鎖"), 0) { sub = it; expand = false; stopLive(); load() }
    private val periodChips = Chips(a, listOf("今天", "7 天", "30 天", "本月"), 1) { pi = it; expand = false; load() }
    private val typeChips = Chips(a, listOf("行動網路", "Wi-Fi"), 0) { ti = it; expand = false; load() }

    init {
        col.addView(subChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    private class Data(
        val mobile: LongArray?, val wifi: LongArray?, val cycleMobile: Long?, val apps: List<NetUsage.App>?,
        val labels: List<String>, val values: List<Float>, val chartNote: String, val err: String
    )

    override fun load() {
        when (sub) {
            0, 2 -> loadStats()
            1 -> showLive()
            else -> { body.removeAllViews(); renderBlockTab() }
        }
    }

    override fun onHide() { stopLive() }

    // =====================================================================  概覽 / 各 App
    private fun loadStats() {
        body.removeAllViews()
        body.addView(periodChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))
        if (!UsageAccess.granted(ctx)) { body.addView(Ui.usageBanner(ctx)); return }
        body.addView(Ui.note(ctx, "讀取中…"))
        val period = pi; val type = if (ti == 0) NetUsage.MOBILE else NetUsage.WIFI; val s = sub
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

            val labels = ArrayList<String>(); val vals = ArrayList<Float>(); var note = ""
            if (s == 0) {
                if (period == 0) {
                    val h = hourlyFromSamples(today, type, now)
                    labels.addAll(h.first); vals.addAll(h.second)
                    note = "每小時用量取自本機取樣(開啟監測期間);總量取自系統流量統計。"
                } else {
                    val sdf = SimpleDateFormat("M/d", Locale.getDefault())
                    var d0 = AppUsage.dayStart(from)
                    while (d0 <= now && labels.size < 31) {
                        val v = tryq { NetUsage.total(ctx, type, d0, minOf(d0 + AppUsage.DAY, now)) }
                        labels.add(sdf.format(Date(d0 + 3_600_000L))); vals.add(if (v == null) 0f else (v[0] + v[1]).toFloat())
                        d0 += AppUsage.DAY
                    }
                    note = "每日用量取自系統流量統計(與系統設定的數據用量一致)。"
                }
            }
            val data = Data(mobile, wifi, cm?.let { it[0] + it[1] }, apps, labels, vals, note, err)
            ui.post {
                try { render(data, type) } catch (e: Throwable) {
                    body.removeAllViews(); body.addView(Ui.note(ctx, "顯示錯誤:${e.javaClass.simpleName}: ${e.message}", Theme.RED))
                }
            }
        }
    }

    /** 今天每小時流量(本機取樣的流量計數差) */
    private fun hourlyFromSamples(day: Long, type: Int, now: Long): Pair<List<String>, List<Float>> {
        val rows = Db.get(ctx).between(day, day + AppUsage.DAY)
        val vals = FloatArray(24)
        val tz = java.util.TimeZone.getDefault().getOffset(day)
        for (k in 1 until rows.size) {
            val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
            if (dt <= 0 || dt > 65 * 60_000L) continue
            val tot = if (a.rxBytes >= 0 && b.rxBytes >= a.rxBytes) (b.rxBytes - a.rxBytes) + maxOf(0L, b.txBytes - a.txBytes) else 0L
            val mob = if (a.mobileRx >= 0 && b.mobileRx >= a.mobileRx) (b.mobileRx - a.mobileRx) + maxOf(0L, b.mobileTx - a.mobileTx) else 0L
            val v = if (type == NetUsage.MOBILE) mob else maxOf(0L, tot - mob)
            vals[(((b.ts + tz) / 3_600_000L) % 24).toInt()] += v.toFloat()
        }
        val hourNow = (((now + tz) / 3_600_000L) % 24).toInt()
        return Pair((0..hourNow).map { "$it" }, (0..hourNow).map { vals[it] })
    }

    private fun statCol(label: String, v: LongArray?, color: Int): LinearLayout {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Ui.tv(ctx, label, 12f, Theme.SUB))
        c.addView(Ui.tv(ctx, v?.let { fmtBytes(it[0] + it[1]) } ?: "—", 24f, color, true))
        c.addView(Ui.tv(ctx, v?.let { "↓ ${fmtBytes(it[0])}  ↑ ${fmtBytes(it[1])}" } ?: "不支援", 11f, Theme.SUB))
        return c
    }

    private fun render(d: Data, type: Int) {
        body.removeAllViews()
        body.addView(periodChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))
        if (d.mobile == null && d.wifi == null) {
            body.addView(Ui.note(ctx, "無法讀取網路用量:${d.err}\n(部分機型或系統版本限制了行動網路統計)", Theme.AMBER)); return
        }
        if (sub == 0) overview(d, type) else appsTab(d, type)
    }

    private fun overview(d: Data, type: Int) {
        val tname = if (type == 0) "行動網路" else "Wi-Fi"
        val hero = Ui.card(ctx, 24)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(statCol("行動網路", d.mobile, Theme.AMBER), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(statCol("Wi-Fi", d.wifi, Theme.BLUE), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        hero.addView(row)
        val mt = d.mobile?.let { it[0] + it[1] } ?: 0L; val wt = d.wifi?.let { it[0] + it[1] } ?: 0L
        if (mt + wt > 0) {
            val frac = wt.toFloat() / (mt + wt)
            hero.addView(MeterView(ctx).apply { value = frac; color = Theme.BLUE }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 12, 4))
            hero.addView(Ui.tv(ctx, "Wi-Fi 占 ${(frac * 100).toInt()}% · 行動網路占 ${100 - (frac * 100).toInt()}%", 11f, Theme.SUB))
        }
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val limitGb = Prefs.dataLimitGb(ctx); val used = d.cycleMobile ?: 0L
        val mc = Ui.card(ctx)
        val mh = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mh.addView(Ui.tv(ctx, "本月行動數據(每月 ${Prefs.billingDay(ctx)} 號結算)", 14f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        mh.addView(Ui.tv(ctx, "⚙ 設定", 13f, Theme.BLUE, true).apply {
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4)); setOnClickListener { limitSettings(limitGb) }
        })
        mc.addView(mh)
        if (limitGb > 0) {
            val p = used / (limitGb * 1_073_741_824f)
            mc.addView(Ui.tv(ctx, "${fmtBytes(used)} / %.1f GB · %d%%".format(limitGb, (p * 100).toInt()), 13f, Theme.TEXT), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            mc.addView(MeterView(ctx).apply { value = p.coerceIn(0f, 1f); color = Theme.usage(p) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 8, 0))
        } else mc.addView(Ui.tv(ctx, "${fmtBytes(used)} · 尚未設定月上限(點右上角設定)", 13f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(mc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))

        body.addView(typeChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        val bar = BarChartView(ctx).apply {
            title = "$tname " + if (pi == 0) "每小時用量" else "每日用量"
            color = if (type == 0) Theme.AMBER else Theme.BLUE; emptyText = "這段期間沒有流量"
            fmt = { fmtBytes(it.toLong()) }; maxFmt = { fmtBytes(it.toLong()) }; set(d.labels, d.values)
        }
        body.addView(Ui.chartCard(ctx, bar, 180), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
        if (d.chartNote.isNotEmpty()) body.addView(Ui.note(ctx, d.chartNote))

        val apps = d.apps ?: emptyList()
        val fg = apps.sumOf { it.fg }; val bgT = apps.sumOf { it.bg }
        val ratio = if (fg + bgT > 0) bgT.toFloat() / (fg + bgT) else 0f
        val heavy = apps.count { it.bg > it.total * 0.5 && it.bg > 30L * 1_048_576 }
        val bc = Ui.card(ctx)
        bc.addView(Ui.tv(ctx, "背景流量占比($tname)", 14f, Theme.TEXT, true))
        bc.addView(Ui.tv(ctx, "%.0f%%".format(ratio * 100), 26f, Theme.usage(ratio / 0.6f), true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 0))
        bc.addView(MeterView(ctx).apply { value = ratio; color = Theme.usage(ratio / 0.6f) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 4, 6))
        val devTotal = if (type == NetUsage.MOBILE) mt else wt
        val other = (devTotal - fg - bgT).coerceAtLeast(0L)
        bc.addView(Ui.tv(ctx, "前景 ${fmtBytes(fg)} · 背景 ${fmtBytes(bgT)}" + (if (other > 0) " · 其他/系統 ${fmtBytes(other)}" else "") +
            if (heavy > 0) " · ⚠ $heavy 個 App 背景流量偏高" else "", 11f, Theme.SUB))
        body.addView(bc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.note(ctx, "「其他/系統」= 裝置總量 − 各 App 加總(例如網路共享、已移除 App、系統服務)。系統以約 2 小時為一個統計區段,所以「今天」的總量最準,更細的時間切分是估計值。"))
    }

    private fun limitSettings(limitGb: Float) {
        AlertDialog.Builder(ctx).setTitle("流量設定").setItems(arrayOf("每月流量上限", "帳單結算日")) { _, w ->
            if (w == 0) {
                val names = arrayOf("不設定", "1 GB", "3 GB", "5 GB", "10 GB", "20 GB", "50 GB", "100 GB", "自訂…")
                val gbs = floatArrayOf(0f, 1f, 3f, 5f, 10f, 20f, 50f, 100f)
                AlertDialog.Builder(ctx).setTitle("每月行動數據上限").setItems(names) { _, i ->
                    if (i < gbs.size) { Prefs.setDataLimitGb(ctx, gbs[i]); load() }
                    else Ui.askNumber(ctx, "每月上限(GB)", "例如 12.5", if (limitGb > 0) "%.1f".format(limitGb) else "", true) { Prefs.setDataLimitGb(ctx, it.toFloat()); load() }
                }.show()
            } else {
                val days = intArrayOf(1, 5, 10, 15, 20, 25, 28)
                AlertDialog.Builder(ctx).setTitle("每月幾號結算").setItems(days.map { "$it 號" }.toTypedArray()) { _, i -> Prefs.setBillingDay(ctx, days[i]); load() }.show()
            }
        }.show()
    }

    private fun appsTab(d: Data, type: Int) {
        val tname = if (type == 0) "行動網路" else "Wi-Fi"
        body.addView(typeChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        val apps = d.apps ?: emptyList()
        body.addView(Ui.note(ctx, "$tname · 共 ${apps.size} 個 App 有流量 · 點選可看近 7 天明細、封鎖網路"))
        val list = Ui.card(ctx)
        val top = apps.take(40)
        val shown = if (expand) top else top.take(10)
        if (top.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有流量紀錄。"))
        val maxB = (top.firstOrNull()?.total ?: 1L).toFloat()
        val nb = Prefs.netBlocked(ctx)
        for (s in shown) {
            val (name, pkg) = NetUsage.uidName(ctx, s.uid)
            val heavyBg = s.bg > s.total * 0.5 && s.bg > 30L * 1_048_576
            val badge = when { pkg != null && pkg in nb -> "網路封鎖"; heavyBg -> "背景偏高"; else -> "" }
            val r = AppRow(ctx)
            r.set(pkg?.let { Apps.icon(ctx, it) }, name, "前景 ${fmtBytes(s.fg)} · 背景 ${fmtBytes(s.bg)}", fmtBytes(s.total), s.total / maxB,
                if (heavyBg) Theme.RED else Theme.BLUE, badge, Theme.RED)
            r.setOnClickListener { appDetail(s.uid, name, pkg) }
            list.addView(r)
        }
        if (top.size > 10) list.addView(Ui.btn(ctx, if (expand) "收合" else "顯示全部 ${top.size} 個", Theme.CARD2, Theme.TEXT) { expand = !expand; render(d, type) }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(list, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.note(ctx, "「背景偏高」= 背景流量超過一半且大於 30 MB,可能是自動同步、廣告或可疑上傳。"))
    }

    private fun appDetail(uid: Int, name: String, pkg: String?) {
        Thread {
            val days = NetUsage.uidDaily(ctx, uid, 7)
            val sdf = SimpleDateFormat("M/d (E)", Locale.getDefault())
            val msg = days.reversed().joinToString("\n") { "${sdf.format(Date(it.first + 12 * 3_600_000L))}   行動 ${fmtBytes(it.second)}  ·  Wi-Fi ${fmtBytes(it.third)}" } +
                "\n\n合計  行動 ${fmtBytes(days.sumOf { it.second })}  ·  Wi-Fi ${fmtBytes(days.sumOf { it.third })}"
            ctx.runOnUiThread {
                val b = AlertDialog.Builder(ctx).setTitle("$name · 近 7 天").setMessage(msg).setNegativeButton("關閉", null)
                if (pkg != null) {
                    val blocked = pkg in Prefs.netBlocked(ctx)
                    b.setPositiveButton(if (blocked) "解除網路封鎖" else "封鎖網路") { _, _ -> Lock.gate(ctx, blocked) { toggleNetBlock(ctx, pkg); load() } }
                    b.setNeutralButton("App 資訊") { _, _ -> Apps.openInfo(ctx, pkg) }
                }
                b.show()
            }
        }.start()
    }

    // =====================================================================  即時
    private var liveOn = false
    private var lastT = 0L; private var lastRx = -1L; private var lastTx = -1L; private var lastMrx = -1L; private var lastMtx = -1L
    private val hist = 90
    private val dn = FloatArray(hist); private val up = FloatArray(hist); private var cnt = 0
    private var peakDn = 0f; private var peakUp = 0f; private var sessRx = 0L; private var sessTx = 0L; private var sessMob = 0L; private var ticks = 0
    private var tvDn: TextView? = null; private var tvUp: TextView? = null; private var tvMix: TextView? = null; private var tvStat: TextView? = null
    private var chart: LineChartView? = null
    private var connBox: LinearLayout? = null; private var appsBox: LinearLayout? = null
    private var prevApp: Map<Int, Long>? = null; private var appBusy = false

    private fun showLive() {
        body.removeAllViews()
        stopLive()
        cnt = 0; peakDn = 0f; peakUp = 0f; sessRx = 0L; sessTx = 0L; sessMob = 0L; ticks = 0; lastT = 0L; prevApp = null

        val hero = Ui.card(ctx, 24)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun col(label: String, color: Int): TextView {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            c.addView(Ui.tv(ctx, label, 12f, Theme.SUB))
            val v = Ui.tv(ctx, "—", 26f, color, true); c.addView(v)
            row.addView(c, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)); return v
        }
        tvDn = col("↓ 下載", Theme.GREEN); tvUp = col("↑ 上傳", Theme.BLUE)
        hero.addView(row)
        tvMix = Ui.tv(ctx, "", 11f, Theme.SUB); hero.addView(tvMix, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        chart = LineChartView(ctx).apply { title = "最近 90 秒速度"; xUnit = " 秒"; yMin = 0f; emptyText = "收集中…" }
        body.addView(Ui.chartCard(ctx, chart!!, 190), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val sc = Ui.card(ctx)
        sc.addView(Ui.tv(ctx, "本次觀察", 14f, Theme.TEXT, true))
        tvStat = Ui.tv(ctx, "", 12f, Theme.SUB); sc.addView(tvStat, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 0))
        body.addView(sc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        connBox = Ui.card(ctx); body.addView(connBox, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        body.addView(Ui.section(ctx, "各 App 近即時速度"))
        appsBox = Ui.card(ctx); body.addView(appsBox)
        body.addView(Ui.note(ctx, "整機速度每秒更新(直接讀取網路流量計數,準確)。各 App 速度來自系統流量統計,系統約每數十秒才結算一次,所以是「近即時」的估計,可能呈一陣一陣的跳動。" +
            "若要看逐封包內容(連去哪個網域/IP),需要完整的 VPN 轉發與解析,一般手機 App 無法可靠做到;目前只能在「封鎖」頁看被封鎖 App 的連線嘗試。"))
        liveOn = true
        ui.post(liveTick)
    }

    private fun stopLive() { liveOn = false; ui.removeCallbacks(liveTick) }

    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveOn) return
            val t = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
            val mrx = TrafficStats.getMobileRxBytes(); val mtx = TrafficStats.getMobileTxBytes()
            if (lastT > 0 && rx >= 0 && lastRx >= 0 && tx >= 0) {
                val sec = (t - lastT) / 1000f
                val dR = maxOf(0L, rx - lastRx); val dT = maxOf(0L, tx - lastTx)
                val rDn = dR / sec; val rUp = dT / sec
                val dM = if (mrx >= 0 && lastMrx >= 0) maxOf(0L, mrx - lastMrx) + maxOf(0L, mtx - lastMtx) else 0L
                if (cnt == hist) { System.arraycopy(dn, 1, dn, 0, hist - 1); System.arraycopy(up, 1, up, 0, hist - 1) } else cnt++
                dn[cnt - 1] = rDn; up[cnt - 1] = rUp
                peakDn = maxOf(peakDn, rDn); peakUp = maxOf(peakUp, rUp)
                sessRx += dR; sessTx += dT; sessMob += dM
                tvDn?.text = fmtRate(rDn); tvUp?.text = fmtRate(rUp)
                val tot = dR + dT
                tvMix?.text = if (tot > 0) "目前走 " + (if (dM * 2 > tot) "行動網路" else "Wi-Fi") + " · 行動網路占 ${dM * 100 / tot}%" else "目前沒有流量"
                chartUpdate()
                tvStat?.text = "尖峰:↓ ${fmtRate(peakDn)}  ↑ ${fmtRate(peakUp)}\n" +
                    "累計:↓ ${fmtBytes(sessRx)}  ↑ ${fmtBytes(sessTx)}(行動網路 ${fmtBytes(sessMob)})"
            }
            lastT = t; lastRx = rx; lastTx = tx; lastMrx = mrx; lastMtx = mtx
            if (ticks % 5 == 0) { updateConn(); pollApps() }
            ticks++
            ui.postDelayed(this, 1000L)
        }
    }

    private fun chartUpdate() {
        val c = chart ?: return
        val mx = (0 until cnt).maxOf { maxOf(dn[it], up[it]) }
        val mega = mx >= 1_048_576f
        val k = if (mega) 1f / 1_048_576f else 1f / 1024f
        c.yUnit = if (mega) " MB/s" else " KB/s"
        c.set(listOf(
            Series("↓", Theme.GREEN, FloatArray(cnt) { -(cnt - 1 - it).toFloat() }, FloatArray(cnt) { dn[it] * k }),
            Series("↑", Theme.BLUE, FloatArray(cnt) { -(cnt - 1 - it).toFloat() }, FloatArray(cnt) { up[it] * k })))
    }

    @Suppress("DEPRECATION")
    private fun updateConn() {
        val box = connBox ?: return
        box.removeAllViews()
        box.addView(Ui.tv(ctx, "連線資訊", 14f, Theme.TEXT, true))
        val rows = ArrayList<Pair<String, String>>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val an = cm.activeNetwork
            val caps = if (an == null) null else cm.getNetworkCapabilities(an)
            if (caps == null) rows.add("狀態" to "無網路")
            else {
                val t = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "行動網路"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "乙太網路"
                    else -> "其他"
                }
                rows.add("連線類型" to t)
                rows.add("計費" to if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "不計量(吃到飽)" else "計量網路")
                rows.add("系統估計頻寬" to "↓ ${caps.linkDownstreamBandwidthKbps / 1000} Mbps / ↑ ${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) rows.add("VPN" to "使用中")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                    if (wi != null) {
                        if (wi.linkSpeed > 0) rows.add("Wi-Fi 連線速度" to "${wi.linkSpeed} Mbps")
                        if (wi.rssi > -127) rows.add("Wi-Fi 訊號" to "${wi.rssi} dBm")
                        if (wi.frequency > 0) rows.add("Wi-Fi 頻率" to "${wi.frequency} MHz")
                    }
                }
            }
        } catch (e: Exception) { rows.add("讀取失敗" to (e.message ?: "")) }
        for ((k, v) in rows) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
            r.addView(Ui.tv(ctx, k, 12f, Theme.SUB), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.tv(ctx, v, 12f, Theme.TEXT).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, Ui.WRAP, 1.6f))
            box.addView(r)
        }
    }

    private fun pollApps() {
        if (appBusy) return
        val box = appsBox ?: return
        if (!UsageAccess.granted(ctx)) { box.removeAllViews(); box.addView(Ui.note(ctx, "需要「使用情況存取權」才能顯示各 App 速度。")); return }
        appBusy = true
        Thread {
            val m = HashMap<Int, Long>()
            try {
                val from = AppUsage.dayStart(System.currentTimeMillis()); val now = System.currentTimeMillis()
                for (type in intArrayOf(NetUsage.MOBILE, NetUsage.WIFI)) try {
                    for (a in NetUsage.perApp(ctx, type, from, now)) m[a.uid] = (m[a.uid] ?: 0L) + a.total
                } catch (_: Exception) {}
            } catch (_: Exception) {}
            ui.post {
                appBusy = false
                if (!liveOn) return@post
                val prev = prevApp
                prevApp = m
                val b = appsBox ?: return@post
                b.removeAllViews()
                if (prev == null) { b.addView(Ui.note(ctx, "收集中…")); return@post }
                val deltas = m.map { Pair(it.key, it.value - (prev[it.key] ?: it.value)) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
                if (deltas.isEmpty()) b.addView(Ui.note(ctx, "近 5 秒沒有明顯流量(系統統計約每數十秒更新一次)。"))
                val mx = (deltas.firstOrNull()?.second ?: 1L).toFloat()
                for ((uid, delta) in deltas) {
                    val (name, pkg) = NetUsage.uidName(ctx, uid)
                    val r = AppRow(ctx)
                    r.set(pkg?.let { Apps.icon(ctx, it) }, name, "近 5 秒 +${fmtBytes(delta)}", fmtRate(delta / 5f), delta / mx, Theme.GREEN)
                    b.addView(r)
                }
            }
        }.start()
    }

    // =====================================================================  封鎖
    private fun renderBlockTab() {
        renderBlocker()
        packetCard()
    }

    private fun renderBlocker() {
        val on = Prefs.netBlockOn(ctx); val list = Prefs.netBlocked(ctx)
        val c = Ui.card(ctx)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.tv(ctx, "App 網路封鎖(防偷跑流量)", 16f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.tv(ctx, if (on) "● 運作中" else "○ 未啟用", 12f, if (on) Theme.GREEN else Theme.SUB, true))
        c.addView(head)
        c.addView(Ui.tv(ctx, "被選中的 App 會完全無法上網(含背景同步)。原理是建立一條只給這些 App 使用的本機 VPN,封包全部丟棄,不會經過任何伺服器。" +
            "手機同時只能有一個 VPN,啟用時系統會跳出授權視窗。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))
        if (list.isNotEmpty()) c.addView(Ui.tv(ctx, "已選 ${list.size} 個:" + list.take(5).joinToString("、") { Apps.label(ctx, it) } + if (list.size > 5) "…" else "", 12f, Theme.TEXT), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        c.addView(Ui.btn(ctx, "選擇要封鎖網路的 App", Theme.CARD2, Theme.TEXT) { Lock.gate(ctx, Prefs.netBlocked(ctx).isNotEmpty()) {
            pickApps(ctx, "封鎖網路的 App", Prefs.netBlocked(ctx)) {
                Prefs.setNetBlocked(ctx, it)
                if (it.isEmpty()) NetBlock.stop(ctx) else if (!Prefs.netBlockOn(ctx)) NetBlock.enable(ctx) else NetBlock.refresh(ctx)
                load()
            }
        } })
        if (list.isNotEmpty()) {
            if (on) c.addView(Ui.btn(ctx, "停用網路封鎖", Theme.CARD2, Theme.RED) { Lock.gate(ctx) { NetBlock.stop(ctx); load() } }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            else c.addView(Ui.btn(ctx, "啟用網路封鎖", Theme.GREEN, Ui.DARK_ON_ACCENT) { NetBlock.enable(ctx) }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        }
        body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
    }

    private fun packetCard() {
        val tot = PacketLog.totals()
        val c = Ui.card(ctx)
        c.addView(Ui.tv(ctx, "攔截紀錄(被封鎖 App 嘗試的連線)", 16f, Theme.TEXT, true))
        c.addView(Ui.tv(ctx, "只有被選為「封鎖網路」的 App 會經過這條 VPN,所以這裡能看到它們想連去哪(網域、IP、連接埠)與次數。沒被封鎖的 App 流量不經過本 App,無法逐封包檢視。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))
        if (tot.isEmpty()) c.addView(Ui.note(ctx, "尚無紀錄。啟用網路封鎖後,被擋下的連線嘗試會出現在這裡。"))
        else {
            for ((uid, v) in tot.entries.sortedByDescending { it.value[1] }.take(8)) {
                val (name, pkg) = NetUsage.uidName(ctx, uid)
                val r = AppRow(ctx)
                r.set(pkg?.let { Apps.icon(ctx, it) }, if (uid < 0) "未識別" else name, "嘗試連線 ${v[0]} 個封包", fmtBytes(v[1]), null)
                c.addView(r)
            }
            val dom = PacketLog.topDomains(10)
            if (dom.isNotEmpty()) {
                c.addView(Ui.tv(ctx, "最常嘗試連去的網域", 13f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 10, 4))
                for ((k, n) in dom) {
                    val uid = k.substringBefore('|').toIntOrNull() ?: -1
                    c.addView(Ui.tv(ctx, "${NetUsage.uidName(ctx, uid).first} → ${k.substringAfter('|')}  ×$n", 11f, Theme.SUB))
                }
            }
            c.addView(Ui.tv(ctx, "最近的連線嘗試", 13f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 10, 4))
            val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
            for (p in PacketLog.recent(30)) {
                val who = if (p.uid < 0) "?" else NetUsage.uidName(ctx, p.uid).first
                c.addView(Ui.tv(ctx, "${tf.format(Date(p.ts))} ${p.proto} ${p.dst}:${p.port} ${p.len}B · $who" + if (p.domain.isNotEmpty()) " · ${p.domain}" else "", 11f, Theme.SUB))
            }
        }
        c.addView(Ui.btn(ctx, "重新整理", Theme.CARD2, Theme.TEXT) { load() }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 10, 0))
        c.addView(Ui.btn(ctx, "清除紀錄", Theme.CARD2, Theme.RED) { PacketLog.clear(); load() }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
    }
}
