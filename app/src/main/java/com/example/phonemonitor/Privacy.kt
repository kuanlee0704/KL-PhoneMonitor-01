package com.example.phonemonitor

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings

class AppPerm(
    val pkg: String, val label: String, val perms: List<String>, val specials: List<String>,
    val score: Int, val lastUsed: Long
) {
    val risk: Int get() = if (score >= 8) 2 else if (score >= 4) 1 else 0   // 2 高 / 1 中 / 0 低
}

class Special(val kind: String, val pkg: String)
class AuditResult(val apps: List<AppPerm>, val specials: List<Special>, val usageKnown: Boolean)

object PermAudit {
    private val SENS = linkedMapOf(
        "android.permission.CAMERA" to Pair("相機", 2),
        "android.permission.RECORD_AUDIO" to Pair("麥克風", 2),
        "android.permission.ACCESS_FINE_LOCATION" to Pair("精確位置", 2),
        "android.permission.ACCESS_COARSE_LOCATION" to Pair("概略位置", 1),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Pair("背景定位", 3),
        "android.permission.READ_SMS" to Pair("讀取簡訊", 3),
        "android.permission.RECEIVE_SMS" to Pair("接收簡訊", 2),
        "android.permission.SEND_SMS" to Pair("傳送簡訊", 2),
        "android.permission.READ_CALL_LOG" to Pair("通話紀錄", 2),
        "android.permission.READ_CONTACTS" to Pair("聯絡人", 1),
        "android.permission.READ_PHONE_STATE" to Pair("電話狀態", 1),
        "android.permission.READ_CALENDAR" to Pair("行事曆", 1),
        "android.permission.BODY_SENSORS" to Pair("身體感測器", 1),
        "android.permission.ACTIVITY_RECOGNITION" to Pair("活動辨識", 1),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to Pair("存取所有檔案", 2)
    )

    private fun enabledPkgs(ctx: Context, key: String): Set<String> = try {
        (Settings.Secure.getString(ctx.contentResolver, key) ?: "").split(":")
            .filter { it.contains("/") }.map { it.substringBefore("/") }.toSet()
    } catch (e: Throwable) { emptySet() }

    private fun opAllowed(ctx: Context, op: String, uid: Int, pkg: String): Boolean = try {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(op, uid, pkg) else ao.checkOpNoThrow(op, uid, pkg)
        m == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) { false }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): AuditResult {
        val pm = ctx.packageManager
        val infos: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Throwable) { emptyList() }

        val usage = UsageAccess.granted(ctx)
        val last = if (usage) AppUsage.lastUsedMap(ctx, 30) else emptyMap()

        val acc = enabledPkgs(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val nls = enabledPkgs(ctx, "enabled_notification_listeners")
        val admins: Set<String> = try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            (dpm.activeAdmins ?: emptyList()).map { it.packageName }.toSet()
        } catch (e: Throwable) { emptySet() }

        val specials = ArrayList<Special>()
        val apps = ArrayList<AppPerm>()
        for (pi in infos) {
            val ai = pi.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val pkg = pi.packageName
            if (pkg == ctx.packageName) continue

            // 特殊存取(常被監控軟體濫用),系統 App 也檢查
            val sp = ArrayList<String>()
            if (pkg in acc) sp.add("無障礙服務")
            if (pkg in nls) sp.add("通知存取")
            if (pkg in admins) sp.add("裝置管理員")
            val req = pi.requestedPermissions ?: emptyArray()
            if ("android.permission.SYSTEM_ALERT_WINDOW" in req && opAllowed(ctx, "android:system_alert_window", ai.uid, pkg)) sp.add("懸浮視窗")
            if ("android.permission.REQUEST_INSTALL_PACKAGES" in req && opAllowed(ctx, "android:request_install_packages", ai.uid, pkg)) sp.add("可安裝其他 App")
            for (k in sp) {
                // 系統 App 的通知存取/裝置管理員屬正常,不列入警示
                if (isSystem && (k == "通知存取" || k == "裝置管理員")) continue
                specials.add(Special(k, pkg))
            }

            if (isSystem) continue

            val flags = pi.requestedPermissionsFlags ?: IntArray(0)
            val granted = ArrayList<String>(); var score = 0
            for (idx in req.indices) {
                val v = SENS[req[idx]] ?: continue
                val ok = idx < flags.size && (flags[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (ok) { granted.add(v.first); score += v.second }
            }
            for (k in sp) score += when (k) { "無障礙服務" -> 5; "裝置管理員" -> 5; "通知存取" -> 4; "懸浮視窗" -> 2; else -> 1 }
            if (granted.isEmpty() && sp.isEmpty()) continue
            apps.add(AppPerm(pkg, Apps.label(ctx, pkg), granted, sp, score, last[pkg] ?: 0L))
        }
        return AuditResult(apps.sortedByDescending { it.score }, specials, usage)
    }
}

/** 麥克風 / 相機 使用偵測(背景監測運作期間) */
class SensorWatcher(private val ctx: Context, private val h: Handler) {
    private var micId = -1L
    private val camIds = HashMap<String, Long>()
    private var audioCb: AudioManager.AudioRecordingCallback? = null
    private var camCb: CameraManager.AvailabilityCallback? = null

    fun start() {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) { audio(configs) }
            }
            audioCb = cb
            am.registerAudioRecordingCallback(cb, h)
        } catch (e: Throwable) { Prefs.setErr(ctx, "mic watcher: ${e.message}") }
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) { camStart(cameraId) }
                override fun onCameraAvailable(cameraId: String) { camEnd(cameraId) }
            }
            camCb = cb
            cm.registerAvailabilityCallback(cb, h)
        } catch (e: Throwable) { Prefs.setErr(ctx, "camera watcher: ${e.message}") }
    }

    fun stop() {
        try { audioCb?.let { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(it) } } catch (_: Throwable) {}
        try { camCb?.let { (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).unregisterAvailabilityCallback(it) } } catch (_: Throwable) {}
        val db = Db.get(ctx)
        if (micId >= 0) db.endEvent(micId)
        camIds.values.forEach { db.endEvent(it) }
        micId = -1L; camIds.clear()
    }

    private fun srcName(s: Int) = when (s) {
        0 -> "預設"; 1 -> "麥克風"; 5 -> "錄影"; 6 -> "語音辨識"; 7 -> "通話/通訊"; 9 -> "未處理音訊"
        10 -> "語音表演"; 1999 -> "語音喚醒"; else -> "來源$s"
    }

    private fun audio(cfgs: List<AudioRecordingConfiguration>?) {
        val active = !cfgs.isNullOrEmpty()
        if (active && micId < 0) {
            val srcs = cfgs!!.map { it.clientAudioSource }
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = am.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            val hotword = srcs.all { it == 1999 }
            micId = begin("mic", srcs.joinToString("、") { srcName(it) } + if (inCall) "(通話中)" else "", inCall || hotword)
        } else if (!active && micId >= 0) {
            Db.get(ctx).endEvent(micId); micId = -1L
        }
    }

    private fun camStart(id: String) {
        if (camIds.containsKey(id)) return
        camIds[id] = begin("cam", "相機 $id", false)
    }

    private fun camEnd(id: String) {
        camIds.remove(id)?.let { Db.get(ctx).endEvent(it) }
    }

    private fun begin(kind: String, info: String, benign: Boolean): Long {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val scr = pm.isInteractive
        val fg = AppUsage.foregroundPkg(ctx) ?: ""
        val flag = if (!benign && !scr) 2 else 0
        val id = Db.get(ctx).startEvent(kind, fg, scr, flag, info)
        if (flag == 2) alert(kind)
        return id
    }

    private fun alert(kind: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("alert", "隱私警示", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val what = if (kind == "mic") "麥克風" else "相機"
            nm.notify(if (kind == "mic") 101 else 102,
                Notification.Builder(ctx, "alert").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("螢幕關閉時偵測到${what}被使用")
                    .setContentText("可能是語音助理或錄音 App;若不是你本人操作,請到「隱私」頁檢查。")
                    .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Throwable) {}
    }
}

/** 每日使用上限提醒 */
object LimitChecker {
    fun check(ctx: Context) {
        val limits = Prefs.limits(ctx); val total = Prefs.totalLimit(ctx)
        if (limits.isEmpty() && total <= 0) return
        if (!UsageAccess.granted(ctx)) return
        val now = System.currentTimeMillis()
        val r = AppUsage.compute(ctx, AppUsage.dayStart(now), now)
        if (total > 0) notifyIf(ctx, "total", r.screenOnMs / 60_000L, total, "今日螢幕時間", null)
        for (a in r.apps) limits[a.pkg]?.let { notifyIf(ctx, a.pkg, a.fgMs / 60_000L, it, Apps.label(ctx, a.pkg), a.pkg) }
    }

    private fun notifyIf(ctx: Context, key: String, used: Long, limit: Int, name: String, pkg: String?) {
        val over = used >= limit
        val warn = !over && used >= limit * 0.9
        if (!over && !warn) return
        val tag = (if (over) "o:" else "w:") + key
        if (Prefs.notifiedToday(ctx, tag)) return
        Prefs.markNotified(ctx, tag)
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("limit", "使用時間提醒", NotificationManager.IMPORTANCE_HIGH))
            val intent = if (pkg != null) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")) else Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
            nm.notify(key.hashCode() + if (over) 1 else 0,
                Notification.Builder(ctx, "limit").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(if (over) "$name 已超過今日上限" else "$name 快到今日上限")
                    .setContentText("今日已使用 ${used} 分鐘(上限 $limit 分鐘)" + if (pkg != null) ",點此可開啟 App 資訊" else "")
                    .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Throwable) {}
    }
}
