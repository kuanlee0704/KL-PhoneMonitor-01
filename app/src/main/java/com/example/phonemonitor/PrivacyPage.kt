package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 隱私與權限(重建版):
 * 總覽 → 一眼看狀態與待處理項目;偵測 → 麥克風/相機時間軸;權限 → 敏感權限稽核;
 * 特殊 → 高風險特殊權限;背景 → 長時間背景常駐。
 */
class PrivacyPage(a: Activity) : BasePage(a, "隱私與權限") {
    private class Data(val audit: AuditResult, val events: List<Ev>, val fgs: List<FgsStat>)

    private var data: Data? = null
    private var sub = 0
    private var fi = 0          // 權限篩選:0 全部 / 1 高風險 / 2 閒置有權限
    private var ef = 0          // 事件篩選:0 全部 / 1 僅可疑
    private var cat = ""        // 權限類型篩選

    private val subChips = Chips(a, listOf("總覽", "偵測", "權限", "特殊", "背景"), 0) { sub = it; render() }
    private val permChips = Chips(a, listOf("全部", "高風險", "閒置有權限"), 0) { fi = it; render() }
    private val evChips = Chips(a, listOf("全部", "僅可疑"), 0) { ef = it; render() }

    private val specialKinds = listOf("無障礙服務", "裝置管理員", "通知存取", "懸浮視窗", "可安裝其他 App")
    private val strongKinds = setOf("無障礙服務", "裝置管理員", "通知存取")
    private val explain = mapOf(
        "無障礙服務" to "可讀取螢幕內容、代你操作(監控軟體最常用)",
        "裝置管理員" to "可鎖機、清除資料,且不易被解除安裝",
        "通知存取" to "可讀取所有通知,包含驗證碼",
        "懸浮視窗" to "可蓋在其他 App 上方(釣魚/誘導點擊常用)",
        "可安裝其他 App" to "可在背景下載並安裝其他程式"
    )

    init {
        col.addView(subChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        if (data == null) loading()
        bg {
            val now = System.currentTimeMillis()
            val audit = PermAudit.scan(ctx)
            val ev = Db.get(ctx).events(now - 7 * AppUsage.DAY)
            val fgs = if (UsageAccess.granted(ctx)) try { AppUsage.aggregate(ctx, now - 7 * AppUsage.DAY, now) } catch (e: Throwable) { emptyList() } else emptyList()
            ui.post { data = Data(audit, ev, fgs); render() }
        }
    }

    private fun render() {
        val d = data ?: return
        body.removeAllViews()
        when (sub) { 0 -> overview(d); 1 -> detect(d); 2 -> perms(d); 3 -> specials(d); else -> background(d) }
    }

    private fun jump(i: Int) { sub = i; subChips.select(i); render() }

    private fun fmtShort(ms: Long) = if (ms < 60_000L) "${ms / 1000} 秒" else fmtDur(ms)

    // ------------------------------------------------------------------ 總覽
    private fun overview(d: Data) {
        val tr = Prefs.trusted(ctx)
        val sus = d.events.count { it.flag == 2 }
        val sp = d.audit.specials.filter { it.pkg !in tr && it.kind in strongKinds }
        val high = d.audit.apps.count { it.risk == 2 && it.pkg !in tr }
        val hidden = d.fgs.filter { it.pkg != ctx.packageName && it.pkg !in tr && it.fgsMs >= 10 * 60_000L && it.fgsMs > it.fgMs * 3 }
        val score = sus * 3 + sp.size * 2 + high + hidden.size

        val (label, color, msg) = when {
            score == 0 -> Triple("狀態良好", Theme.GREEN, "沒有發現需要處理的項目。")
            score <= 3 -> Triple("請留意", Theme.AMBER, "有少數項目值得確認是不是你本人設定的。")
            else -> Triple("建議檢查", Theme.RED, "有多個項目需要確認,請從下方清單開始處理。")
        }
        val hero = Ui.card(ctx, 24)
        hero.addView(Ui.tv(ctx, "隱私狀態", 12f, Theme.SUB))
        hero.addView(Ui.tv(ctx, label, 30f, color, true))
        hero.addView(Ui.tv(ctx, msg, 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 2, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        fun tile(title: String, n: Int, sub: String, target: Int, warn: Boolean): MetricCard {
            val c = MetricCard(ctx, title, false)
            c.set("$n", sub, null)
            c.setOnClickListener { jump(target) }
            return c
        }
        Ui.grid(ctx, body,
            tile("螢幕關閉時用麥克風/相機", sus, "近 7 天 · 點選查看", 1, sus > 0),
            tile("高風險特殊權限", sp.size, "無障礙 / 裝置管理 / 通知存取", 3, sp.isNotEmpty()),
            tile("高風險 App", high, "握有多項敏感權限", 2, high > 0),
            tile("背景常駐可疑", hidden.size, "多在背景執行", 4, hidden.isNotEmpty()))

        body.addView(Ui.section(ctx, "建議處理"))
        val todo = Ui.card(ctx)
        var shown = 0
        if (sus > 0) {
            todo.addView(Ui.tv(ctx, "⚠ 有 $sus 次在螢幕關閉時使用麥克風/相機,點此逐筆確認", 13f, Theme.RED, true).apply {
                setPadding(0, ctx.dp(6), 0, ctx.dp(10)); setOnClickListener { ef = 1; evChips.select(1); jump(1) }
            }); shown++
        }
        for (s in sp.take(4)) {
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg), explain[s.kind] ?: "", "", null, Theme.BLUE, s.kind, Theme.RED)
            row.setOnClickListener { riskMenu(s.pkg) }
            todo.addView(row); shown++
        }
        for (f in hidden.take(2)) {
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, f.pkg), Apps.label(ctx, f.pkg), "背景常駐 ${fmtDur(f.fgsMs)},畫面上只用 ${fmtDur(f.fgMs)}", "", null, Theme.BLUE, "多在背景", Theme.AMBER)
            row.setOnClickListener { riskMenu(f.pkg) }
            todo.addView(row); shown++
        }
        if (shown == 0) todo.addView(Ui.note(ctx, "✓ 目前沒有需要處理的項目。"))
        body.addView(todo, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))

        body.addView(Ui.section(ctx, "系統隱私工具"))
        fun tool(label: String, intent: () -> Intent): View = Ui.btn(ctx, label, Theme.CARD2, Theme.TEXT) {
            try { ctx.startActivity(intent()) } catch (e: Exception) { Toast.makeText(ctx, "此手機不支援這個設定頁", Toast.LENGTH_SHORT).show() }
        }.apply { (this as android.widget.TextView).textSize = 12f }
        val tools = ArrayList<View>()
        if (Build.VERSION.SDK_INT >= 31) tools.add(tool("權限使用紀錄") { Intent("android.intent.action.REVIEW_PERMISSION_HISTORY") })
        tools.add(tool("無障礙設定") { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        tools.add(tool("通知存取設定") { Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") })
        tools.add(tool("安全性 / 裝置管理員") { Intent(Settings.ACTION_SECURITY_SETTINGS) })
        tools.add(tool("懸浮視窗存取") { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) })
        tools.add(tool("未知來源安裝") { Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) })
        Ui.grid(ctx, body, *tools.toTypedArray())

        body.addView(Ui.btn(ctx, "重新掃描", Theme.CARD2, Theme.TEXT) { load(); Toast.makeText(ctx, "掃描中…", Toast.LENGTH_SHORT).show() }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))
        body.addView(Ui.note(ctx, "說明:Android 不開放一般 App 讀取「某 App 何時存取了相機/麥克風/位置」的完整歷史,最準的紀錄在系統「權限使用紀錄」(Android 12+)。" +
            "本頁的偵測是依「相機/麥克風被占用的時間點、螢幕狀態、當時前景 App」推斷。標記「信任」的 App 不會列入警示。"))
    }

    // ------------------------------------------------------------------ 偵測
    private fun detect(d: Data) {
        val mic = d.events.count { it.kind == "mic" }; val cam = d.events.count { it.kind == "cam" }
        val sus = d.events.count { it.flag == 2 }
        val a = MetricCard(ctx, "麥克風", false); a.set("$mic 次", "近 7 天", null)
        val b = MetricCard(ctx, "相機", false); b.set("$cam 次", "近 7 天", null)
        val c = MetricCard(ctx, "螢幕關閉時", false); c.set("$sus 次", if (sus > 0) "請逐筆確認" else "沒有異常", null)
        Ui.grid(ctx, body, a, b, c, View(ctx))

        if (!Prefs.enabled(ctx)) body.addView(Ui.note(ctx, "背景監測未開啟,目前不會記錄。請到「設定」開始監測。", Theme.AMBER))
        body.addView(evChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val list = d.events.filter { ef == 0 || it.flag == 2 }.take(80)
        if (list.isEmpty()) { body.addView(Ui.card(ctx).apply { addView(Ui.note(ctx, if (ef == 1) "沒有可疑紀錄。" else "近 7 天沒有紀錄。")) }); return }

        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = dayFmt.format(Date()); val yKey = dayFmt.format(Date(System.currentTimeMillis() - AppUsage.DAY))
        var lastKey = ""
        var card: LinearLayout? = null
        for (e in list) {
            val key = dayFmt.format(Date(e.ts))
            if (key != lastKey) {
                lastKey = key
                body.addView(Ui.section(ctx, when (key) { todayKey -> "今天"; yKey -> "昨天"; else -> fmtTime(e.ts, "M/d(E)") }))
                card = Ui.card(ctx).also { body.addView(it, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4)) }
            }
            card?.addView(eventRow(e))
        }
    }

    private fun eventRow(e: Ev): View {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
        val dur = if (e.endTs > 0) fmtShort(e.endTs - e.ts) else "進行中/未知"
        val sus = e.flag == 2
        r.addView(Ui.tv(ctx, (if (sus) "⚠ " else "") + (if (e.kind == "mic") "🎙 麥克風" else "📷 相機") + "  " + fmtTime(e.ts, "HH:mm") + "  ($dur)",
            13f, if (sus) Theme.RED else Theme.TEXT, true))
        val fgName = if (e.fg.isNotEmpty()) Apps.label(ctx, e.fg) else "未知"
        r.addView(Ui.tv(ctx, "${e.info} · 螢幕${if (e.screenOn) "亮" else "熄"} · 前景:$fgName", 11f, Theme.SUB))
        if (e.fg.isNotEmpty()) r.setOnClickListener { riskMenu(e.fg) }
        return r
    }

    // ------------------------------------------------------------------ 權限
    private fun perms(d: Data) {
        val tr = Prefs.trusted(ctx)
        val now = System.currentTimeMillis()
        body.addView(permChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.btn(ctx, "權限類型:" + (if (cat.isEmpty()) "全部" else cat) + "  ▾", Theme.CARD2, Theme.TEXT) {
            val names = (listOf("全部") + PermAudit.categories).toTypedArray()
            AlertDialog.Builder(ctx).setTitle("依權限類型篩選").setItems(names) { _, w -> cat = if (w == 0) "" else names[w]; render() }.show()
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        if (!d.audit.usageKnown) body.addView(Ui.note(ctx, "尚未開啟「使用情況存取權」,無法判斷「最後使用時間」與「閒置」。", Theme.AMBER))

        val list = d.audit.apps.filter { p ->
            (cat.isEmpty() || cat in p.perms) && when (fi) {
                1 -> p.risk == 2 && p.pkg !in tr
                2 -> d.audit.usageKnown && p.score >= 3 && p.pkg !in tr && (p.lastUsed == 0L || now - p.lastUsed > 14 * AppUsage.DAY)
                else -> true
            }
        }
        body.addView(Ui.note(ctx, "共 ${list.size} 個 App" + if (tr.isNotEmpty()) " · 已信任 ${tr.size} 個" else ""))
        val card = Ui.card(ctx)
        if (list.isEmpty()) card.addView(Ui.note(ctx, "沒有符合條件的 App。"))
        for (p in list.take(40)) {
            val trusted = p.pkg in tr
            val used = if (!d.audit.usageKnown) "" else if (p.lastUsed == 0L) " · 30 天未使用" else " · 最後 " + fmtTime(p.lastUsed, "MM/dd")
            val color = if (trusted) Theme.SUB else when (p.risk) { 2 -> Theme.RED; 1 -> Theme.AMBER; else -> Theme.GREEN }
            val badge = if (trusted) "已信任" else when (p.risk) { 2 -> "高"; 1 -> "中"; else -> "低" }
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, p.pkg), p.label, (p.perms + p.specials).take(4).joinToString("、") + used, "", (p.score / 12f).coerceIn(0f, 1f), color, badge, color)
            row.setOnClickListener { riskMenu(p.pkg, p) }
            card.addView(row)
        }
        body.addView(card)
        if (list.size > 40) body.addView(Ui.note(ctx, "只顯示前 40 個,請用上方篩選縮小範圍。"))
    }

    // ------------------------------------------------------------------ 特殊權限
    private fun specials(d: Data) {
        val tr = Prefs.trusted(ctx)
        var any = false
        for (kind in specialKinds) {
            val items = d.audit.specials.filter { it.kind == kind }
            if (items.isEmpty()) continue
            any = true
            body.addView(Ui.section(ctx, "$kind(${items.size})"))
            val card = Ui.card(ctx)
            card.addView(Ui.tv(ctx, explain[kind] ?: "", 11f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
            for (s in items) {
                val trusted = s.pkg in tr
                val row = AppRow(ctx)
                row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg), if (trusted) "已標記為信任" else "點選查看 / 處理", "", null, Theme.BLUE,
                    if (trusted) "已信任" else if (kind in strongKinds) "留意" else "", if (trusted) Theme.SUB else Theme.RED)
                row.setOnClickListener { riskMenu(s.pkg) }
                card.addView(row)
            }
            body.addView(card, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
        }
        if (!any) body.addView(Ui.card(ctx).apply { addView(Ui.note(ctx, "✓ 沒有發現第三方 App 啟用這些特殊權限。")) })
        body.addView(Ui.note(ctx, "你自己安裝的(密碼管理器、螢幕閱讀器、自動化工具等)出現在這裡屬正常;不認得的才需要處理。"))
    }

    // ------------------------------------------------------------------ 背景
    private fun background(d: Data) {
        if (!d.audit.usageKnown) { body.addView(Ui.usageBanner(ctx)); return }
        val tr = Prefs.trusted(ctx)
        val fgs = d.fgs.filter { it.fgsMs >= 10 * 60_000L && it.pkg != ctx.packageName }.sortedByDescending { it.fgsMs }.take(15)
        body.addView(Ui.note(ctx, "近 7 天,各 App 以「前景服務」在背景常駐的時間。音樂、導航、運動 App 屬正常。"))
        val card = Ui.card(ctx)
        if (fgs.isEmpty()) card.addView(Ui.note(ctx, "沒有 App 長時間在背景常駐。"))
        val maxF = (fgs.firstOrNull()?.fgsMs ?: 1L).toFloat()
        for (f in fgs) {
            val hidden = f.fgsMs > f.fgMs * 3 && f.pkg !in tr
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, f.pkg), Apps.label(ctx, f.pkg), "畫面上使用 ${fmtDur(f.fgMs)}", fmtDur(f.fgsMs), f.fgsMs / maxF,
                if (hidden) Theme.RED else Theme.BLUE, if (f.pkg in tr) "已信任" else if (hidden) "多在背景" else "", if (hidden) Theme.RED else Theme.SUB)
            row.setOnClickListener { riskMenu(f.pkg) }
            card.addView(row)
        }
        body.addView(card)
    }

    // ------------------------------------------------------------------ 共用選單
    private fun riskMenu(pkg: String, p: AppPerm? = null) {
        val label = Apps.label(ctx, pkg)
        val trusted = pkg in Prefs.trusted(ctx)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("開啟 App 資訊(可撤銷權限)" to { Apps.openInfo(ctx, pkg) })
        items.add((if (trusted) "取消信任" else "標記為信任(不再警示)") to { Prefs.toggleTrusted(ctx, pkg); render() })
        items.add("永久封鎖此 App" to {
            Prefs.setBlocked(ctx, Prefs.blocked(ctx) + pkg)
            Toast.makeText(ctx, "已加入封鎖清單(需啟用「專注防護」服務)", Toast.LENGTH_SHORT).show()
        })
        items.add("解除安裝" to { Apps.uninstall(ctx, pkg) })
        val b = AlertDialog.Builder(ctx).setTitle(label)
        if (p != null) b.setMessage("已授予:" + (p.perms + p.specials).joinToString("、"))
        b.setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }
}
