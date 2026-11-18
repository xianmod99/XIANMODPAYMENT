package com.xianmod.payment.listener

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * XIANMOD notification listener.
 *
 * Payment activation stays disabled until the merchant package, notification
 * format, and backend verification contract have been validated.
 */
class GoPayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        setConnected(true)
        Log.i(TAG, "XIANMOD notification listener connected")
    }

    override fun onListenerDisconnected() {
        setConnected(false)
        super.onListenerDisconnected()
        Log.w(TAG, "XIANMOD notification listener disconnected")
        requestRebind(ComponentName(this, GoPayNotificationListener::class.java))
    }

    override fun onDestroy() {
        setConnected(false)
        super.onDestroy()
    }

    private fun setConnected(connected: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putLong(KEY_LAST_STATE_CHANGE, System.currentTimeMillis())
            .apply()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val allowedPackage = MerchantPackageConfig.PACKAGE_NAME
        if (allowedPackage.isBlank() || sbn.packageName != allowedPackage) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        // Never persist or log raw merchant notification content.
        Log.i(TAG, "Configured merchant notification received")

        val parsed = NotificationParser.parse(title, text, bigText)
        if (parsed == null) {
            Log.w(TAG, "Notification is not a validated payment event")
            return
        }

        // Candidate only. No API call and no VIP activation until the
        // real GoPay notification schema and XIANMOD backend are integrated.
        Log.i(TAG, "Payment candidate received; requires server verification")
    }

    companion object {
        private const val TAG = "XIANMOD.Listener"
        const val PREFS = "xianmod_listener_state"
        const val KEY_CONNECTED = "listener_connected"
        const val KEY_LAST_STATE_CHANGE = "listener_last_state_change"
    }
}

object MerchantPackageConfig {
    // Set only after verifying the exact installed GoPay Merchant package.
    const val PACKAGE_NAME = ""
}

data class ParsedPaymentCandidate(
    val amount: Long,
    val transactionReference: String?
)

object NotificationParser {
    /**
     * Deliberately disabled until sanitized real notification examples exist.
     * A guessed parser could misread unrelated notifications as payments.
     */
    fun parse(title: String, text: String, bigText: String): ParsedPaymentCandidate? = null
}
