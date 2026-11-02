package com.falseend.mousecursor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import rikka.shizuku.Shizuku

/** 前景服務：綁定 Shizuku 的 [MouseReaderService]，把滑鼠位移餵給 [CursorHub] */
class CursorService : Service() {

    private var reader: IMouseReader? = null
    private var bound = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, MouseReaderService::class.java.name))
            .daemon(false)
            .processNameSuffix("reader")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val callback = object : IMouseCallback.Stub() {
        override fun onMotion(dx: Int, dy: Int, wheel: Int, buttons: Int, timeUs: Long) {
            CursorHub.onRawMotion(dx, dy, buttons, timeUs)
        }

        override fun onDevices(description: String) {
            devices = description
            updateNotification()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val r = IMouseReader.Stub.asInterface(binder)
            reader = r
            try {
                devices = r.start(callback)
                status = "執行中（uid ${r.uid()}）"
            } catch (e: Exception) {
                status = "啟動失敗：$e"
            }
            updateNotification()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reader = null
            status = "Shizuku 連線中斷"
            updateNotification()
        }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        status = "Shizuku 已停止（手機重開機後要重新啟動 Shizuku）"
        reader = null
        bound = false
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CursorHub.init(this)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        running = true
        CursorHub.acquire(TAG_HUB)
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RECENTER -> CursorHub.recenter()
            ACTION_TOGGLE -> {
                if (CursorHub.isShowing) CursorHub.release(TAG_HUB) else CursorHub.acquire(TAG_HUB)
            }
        }
        bindReader()
        return START_STICKY
    }

    private fun bindReader() {
        if (bound && reader != null) return
        try {
            if (!Shizuku.pingBinder()) {
                status = "Shizuku 未執行"; updateNotification(); return
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status = "尚未授權 Shizuku"; updateNotification(); return
            }
            Shizuku.bindUserService(userServiceArgs, connection)
            bound = true
            status = "連線 Shizuku 中…"
        } catch (e: Throwable) {
            Log.e(TAG, "bind 失敗", e)
            status = "Shizuku 錯誤：$e"
        }
        updateNotification()
    }

    override fun onDestroy() {
        running = false
        Shizuku.removeBinderDeadListener(binderDead)
        runCatching { reader?.stop() }
        runCatching { if (bound) Shizuku.unbindUserService(userServiceArgs, connection, true) }
        reader = null
        bound = false
        CursorHub.release(TAG_HUB)
        status = "已停止"
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "游標服務", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, CursorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cursor)
            .setContentTitle("滑鼠游標：$status")
            .setContentText(devices.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(devices))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "置中", pi(ACTION_RECENTER, 1))
            .addAction(0, "顯示/隱藏", pi(ACTION_TOGGLE, 2))
            .addAction(0, "停止", pi(ACTION_STOP, 3))
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    companion object {
        private const val TAG = "CursorService"
        private const val TAG_HUB = "shizuku"
        private const val CHANNEL = "cursor"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "stop"
        const val ACTION_RECENTER = "recenter"
        const val ACTION_TOGGLE = "toggle"

        @Volatile var running = false
        @Volatile var status = "未啟動"
        @Volatile var devices = ""

        fun start(context: Context) {
            val i = Intent(context, CursorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CursorService::class.java))
        }
    }
}
