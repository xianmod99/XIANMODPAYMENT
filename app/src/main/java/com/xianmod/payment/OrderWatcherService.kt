package com.xianmod.payment

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Memantau order PENDING di latar belakang dan menampilkan notifikasi untuk setiap order baru,
 * walau aplikasi ditutup. Hanya membaca daftar order; tidak pernah menyetujui apa pun.
 */
class OrderWatcherService : Service() {
    private var executor: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib memanggil startForeground dulu agar tidak crash setelah startForegroundService().
        val notification = NotificationCompat.Builder(this, CHANNEL_WATCHER)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle("XIANMOD PAYMENT aktif")
            .setContentText("Memantau order PENDING baru")
            .setOngoing(true)
            .setContentIntent(openAppIntent(this))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
        if (!isEnabled(this) || DeviceTokenStore.token(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor().also {
                it.scheduleWithFixedDelay({ pollOnce() }, 0, POLL_SECONDS, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    private fun pollOnce() {
        try {
            if (!isEnabled(this)) { stopSelf(); return }
            val orders = PaymentApi.fetchOrders(this)
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            orders.filter { it.orderId !in seen }.forEach { notifyOrder(it) }
            // Simpan hanya order yang masih PENDING supaya daftar tidak membesar.
            prefs.edit().putStringSet(KEY_SEEN, orders.map { it.orderId }.toSet()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Polling order gagal: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyOrder(order: PaymentApi.PendingOrder) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val body = OrderNotificationText.body(order)
        val notification = NotificationCompat.Builder(this, CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle(OrderNotificationText.title())
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(this))
            .build()
        manager.notify(order.orderId.hashCode(), notification)
    }

    companion object {
        private const val TAG = "XIANMOD.Watcher"
        private const val PREFS = "xianmod_watcher"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SEEN = "seen_orders"
        private const val CHANNEL_ORDERS = "xianmod_orders"
        private const val CHANNEL_WATCHER = "xianmod_watcher"
        private const val FOREGROUND_ID = 7001
        private const val POLL_SECONDS = 8L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            if (!isEnabled(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, OrderWatcherService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Layanan pemantau tidak bisa dijalankan: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderWatcherService::class.java))
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ORDERS, "Order baru", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Pemberitahuan saat ada order PENDING baru" }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_WATCHER, "Pemantau order", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Penanda bahwa aplikasi sedang memantau order" }
            )
        }
    }
}
