package com.perppilot.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the real-time monitor after a reboot or app update if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { MonitorService.sync(context) }
        }
    }
}
