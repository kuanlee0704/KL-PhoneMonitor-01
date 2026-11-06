package com.example.phonemonitor

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/** 一個需要使用者同意的權限 / 設定 */
class PermItem(
    val key: String, val emoji: String, val title: String, val desc: String, val needFor: String, val required: Boolean,
    val check: (Context) -> Boolean?,          // true 已授予 / false 未授予 / null 無法判斷
    val action: (Activity) -> Unit
)

object Perms {
    const val REQ_NOTIF = 77
    const val REQ_VPN = 201

    private fun openAppNotif(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) } catch (_: Exception) { Apps.openInfo(a, a.packageName) }
    }

    private fun autoStart(a: Activity) {
        val targets = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        for ((pkg, cls) in targets) {
            try { a.startActivity(Intent().setComponent(ComponentName(pkg, cls))); return } catch (_: Exception) {}
        }
        Toast.makeText(a, "找不到品牌專屬頁面,請在 App 資訊 →「電池 / 啟動管理」允許自動啟動與背景執行", Toast.LENGTH_LONG).show()
        Apps.openInfo(a, a.packageName)
    }

    fun items(): List<PermItem> {
        val list = ArrayList<PermItem>()
        list.add(PermItem("notif", "🔔", "通知", "顯示背景監測的常駐通知、使用時間提醒、麥克風/相機警示。", "背景監測、提醒、警示", true,
            { c ->
                val nm = c.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 33) c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && nm.areNotificationsEnabled()
                else nm.areNotificationsEnabled()
            },
            { a ->
                if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !Prefs.flag(a, "notifAsked")) {
                    Prefs.setFlag(a, "notifAsked", true)
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                } else openAppNotif(a)
            }))
        list.add(PermItem("usage", "📊", "使用情況存取權", "讀取各 App 的使用時間、網路用量與前景狀態。資料只在本機計算。", "應用 / 網路 / 隱私 / 使用上限", true,
            { c -> UsageAccess.granted(c) }, { a -> UsageAccess.open(a) }))
        list.add(PermItem("a11y", "🛡", "無障礙服務(專注防護)", "偵測前景 App 與畫面分頁,才能封鎖 App、專注模式、擋 Reels / 主頁。不記錄也不傳送畫面內容。", "封鎖 / 專注 / 短影音防護", true,
            { c -> GuardService.enabled(c) },
            { a ->
                try { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                Toast.makeText(a, "找到「手機監測 · 專注防護」並開啟。若灰色,先完成下方「允許受限制的設定」", Toast.LENGTH_LONG).show()
            }))
        list.add(PermItem("battery", "🔋", "忽略電池最佳化", "避免系統為了省電把背景監測殺掉,紀錄才不會中斷。", "持續記錄", true,
            { c -> (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName) },
            { a ->
                try { a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) }
                catch (_: Exception) { try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
            }))
        if (Build.VERSION.SDK_INT >= 33) list.add(PermItem("restricted", "🔓", "允許受限制的設定", "Android 13+ 對非商店安裝的 App 預設鎖住無障礙等選項。到 App 資訊 → 右上角「⋮」→「允許受限制的設定」。", "能開啟無障礙服務", false,
            { _ -> null }, { a -> Apps.openInfo(a, a.packageName); Toast.makeText(a, "點右上角「⋮」→「允許受限制的設定」", Toast.LENGTH_LONG).show() }))
        list.add(PermItem("autostart", "🚀", "自啟動 / 背景執行(手機品牌設定)", "小米、OPPO、vivo、華為、三星等會額外限制背景程式。請允許本 App 自動啟動、不被清理。", "開機後自動恢復監測", false,
            { _ -> null }, { a -> autoStart(a) }))
        list.add(PermItem("vpn", "🔒", "VPN 授權(網路封鎖)", "網路封鎖用本機 VPN 擋住指定 App 的連線,系統需要你同意一次。不會經過任何伺服器。", "App 網路封鎖", false,
            { c -> try { VpnService.prepare(c) == null } catch (_: Exception) { null } },
            { a ->
                val i = VpnService.prepare(a)
                if (i != null) a.startActivityForResult(i, REQ_VPN) else Toast.makeText(a, "已授權", Toast.LENGTH_SHORT).show()
            }))
        list.add(PermItem("backup", "💾", "備份檔位置", "選一個資料夾存備份檔,解除安裝或換手機後可還原紀錄與設定。", "資料備份 / 還原", false,
            { c -> Prefs.backupUri(c).isNotEmpty() }, { a -> Backup.pickLocation(a) }))
        return list
    }

    fun missing(c: Context): List<PermItem> = items().filter { it.required && it.check(c) == false }
}

/** 權限中心:一處看完所有需要同意的權限,並可一鍵逐項引導 */
class PermPage(a: Activity) : BasePage(a, "權限中心") {
    private var wizard = false
    private val skipped = HashSet<String>()
    private var lastKey = ""
    private var tries = 0

    init { col.addView(body) }

    override fun load() {
        body.removeAllViews()
        val items = Perms.items()
        val req = items.filter { it.required }
        val ok = req.count { it.check(ctx) == true }

        val hero = Ui.card(ctx, 24)
        hero.addView(Ui.tv(ctx, "必要權限", 12f, Theme.SUB))
        hero.addView(Ui.tv(ctx, "$ok / ${req.size} 已完成", 28f, if (ok == req.size) Theme.GREEN else Theme.AMBER, true))
        hero.addView(MeterView(ctx).apply { value = ok.toFloat() / maxOf(1, req.size); color = if (ok == req.size) Theme.GREEN else Theme.AMBER }, Ui.lp(ctx, Ui.MATCH, ctx.dp(8), 8, 10))
        if (ok < req.size) hero.addView(Ui.btn(ctx, "一鍵引導:逐項完成所有權限", Theme.GREEN, Ui.DARK_ON_ACCENT) { startWizard() })
        else hero.addView(Ui.tv(ctx, "✓ 所有必要權限都已授予。下方選填項目依需求設定。", 12f, Theme.SUB))
        hero.addView(Ui.tv(ctx, "Android 規定這些都必須由你在系統畫面親自同意,App 無法代按。按下按鈕會直接帶你到對應頁面,完成後回來會自動接續下一項。", 11f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))

        body.addView(Ui.section(ctx, "必要"))
        items.filter { it.required }.forEach { card(it) }
        body.addView(Ui.section(ctx, "選填(依你要用的功能)"))
        items.filter { !it.required }.forEach { card(it) }
        body.addView(Ui.note(ctx, "本 App 沒有網路(INTERNET)權限,所有資料只存在你的手機。"))
    }

    private fun card(p: PermItem) {
        val st = p.check(ctx)
        val c = Ui.card(ctx)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.tv(ctx, "${p.emoji}  ${p.title}", 15f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.tv(ctx, when (st) { true -> "✓ 已授予"; false -> "✗ 未授予"; else -> "需自行確認" }, 12f, when (st) { true -> Theme.GREEN; false -> Theme.RED; else -> Theme.AMBER }, true))
        c.addView(head)
        c.addView(Ui.tv(ctx, p.desc, 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 2))
        c.addView(Ui.tv(ctx, "用於:${p.needFor}", 11f, Theme.SUB))
        c.addView(Ui.btn(ctx, if (st == true) "重新設定 / 檢視" else "前往授予", if (st == true) Theme.CARD2 else Theme.BLUE, if (st == true) Theme.TEXT else Ui.DARK_ON_ACCENT) { p.action(ctx) }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
    }

    // ---------- 一鍵引導 ----------
    fun startWizard() { wizard = true; skipped.clear(); lastKey = ""; tries = 0; step() }

    private fun step() {
        val todo = Perms.items().filter { (it.required && it.check(ctx) == false) && it.key !in skipped }
        // 若網路封鎖已有設定但 VPN 還沒授權,也一併處理
        val extra = if (Prefs.netBlocked(ctx).isNotEmpty()) Perms.items().filter { it.key == "vpn" && it.check(ctx) == false && it.key !in skipped } else emptyList()
        val next = (todo + extra).firstOrNull()
        if (next == null) {
            wizard = false
            Toast.makeText(ctx, if (skipped.isEmpty()) "全部完成 🎉" else "已處理完,部分項目請手動確認", Toast.LENGTH_LONG).show()
            load(); return
        }
        if (next.key == lastKey) tries++ else { lastKey = next.key; tries = 1 }
        if (tries > 2) { skipped.add(next.key); step(); return }   // 同一項回來兩次仍未授予 → 先略過,避免無限迴圈
        Toast.makeText(ctx, "步驟:${next.title}", Toast.LENGTH_SHORT).show()
        next.action(ctx)
    }

    /** 從系統設定頁或權限對話框回來時呼叫 */
    fun onReturn() {
        if (wizard) ui.postDelayed({ step() }, 350) else load()
    }
}
