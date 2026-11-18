package com.xianmod.payment.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listener scaffold.
 *
 * IMPORTANT:
 * 1. Set the exact GoPay Merchant package name only after verifying it on-device.
 * 2. Do not treat notification text/amount alone as proof of settled payment.
 * 3. Keep backend matching in MANUAL_REVIEW until real notification formats
 *    and transaction references have been validated.
 */
class GoPayNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected")
        requestRebind(android.content.ComponentName(this, GoPayNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val allowedPackage = MerchantPackageConfig.PACKAGE_NAME
        if (allowedPackage.isBlank() || sbn.packageName != allowedPackage) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        // Intentionally log only that an event arrived; never log raw notification content.
        Log.i(TAG, "Merchant notification received; parser validation required")

        val parsed = NotificationParser.parse(title, text, bigText)
        if (parsed == null) {
            Log.w(TAG, "Notification did not match a validated payment pattern")
            return
        }

        // TODO: Persist a redacted event locally and submit through authenticated API.
        // No automatic activation is permitted in this scaffold.
        Log.i(TAG, "Parsed candidate amount=${parsed.amount}; status=MANUAL_REVIEW")
    }

    companion object {
        private const val TAG = "XIANMOD.Listener"
    }
}

object MerchantPackageConfig {
    // Replace only after confirming the actual installed merchant app package.
    const val PACKAGE_NAME = ""
}

data class ParsedPaymentCandidate(
    val amount: Long,
    val transactionReference: String?
)

object NotificationParser {
    /**
     * Safe placeholder. Real parser must be written from sanitized examples
     * of the merchant's actual incoming-payment notifications.
     */
    fun parse(title: String, text: String, bigText: String): ParsedPaymentCandidate? {
        return null
    }
}
