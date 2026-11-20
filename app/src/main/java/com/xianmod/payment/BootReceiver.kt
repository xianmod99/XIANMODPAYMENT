package com.xianmod.payment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Menjalankan lagi pemantau order setelah HP restart (hanya bila perangkat sudah dipasangkan). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && DeviceTokenStore.token(context) != null) {
            OrderWatcherService.start(context)
        }
    }
}
