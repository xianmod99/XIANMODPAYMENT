package com.xianmod.payment.listener

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xianmod.payment.PaymentApi

/**
 * XIANMOD notification listener.
 *
 * Only the verified GoPay Merchant static-QR payment notification format is parsed.
 * Parsed notifications are untrusted candidates and never trigger automatic activation.
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

        // Notification data is an untrusted candidate. Backend still requires Owner approval.
        Thread {
            try {
                PaymentApi.postCandidate(this, sbn.packageName, parsed.amount, parsed.transactionReference, sbn.postTime)
                Log.i(TAG, "Payment candidate forwarded for Owner review")
            } catch (e: Exception) {
                Log.w(TAG, "Candidate could not be sent to API: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "XIANMOD.Listener"
        const val PREFS = "xianmod_listener_state"
        const val KEY_CONNECTED = "listener_connected"
        const val KEY_LAST_STATE_CHANGE = "listener_last_state_change"
    }
}

object MerchantPackageConfig {
    // Official Google Play package ID for GoPay Merchant: QRIS.
    const val PACKAGE_NAME = "com.gojek.gopaymerchant"
}

data class ParsedPaymentCandidate(
    val amount: Long,
    val transactionReference: String?
)

object NotificationParser {
    private const val VERIFIED_TITLE = "Pembayaran QRIS statis diterima"

    // Verified on-device example: "Rp 1 di DIM QR."
    // Dots are treated as Indonesian thousands separators; decimals are rejected.
    private val amountLine = Regex(
        pattern = "^\\s*Rp\\s*([0-9][0-9.]*)\\s+di\\s+(.+?)\\.?\\s*$",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Accept only the confirmed static-QR success title and a matching amount line.
     * No transaction ID is present in the observed notification, so reference stays null.
     */
    fun parse(title: String, text: String, bigText: String): ParsedPaymentCandidate? {
        if (title.trim().replace(Regex("\\s+"), " ") != VERIFIED_TITLE) return null

        val parsedLines = listOf(text, bigText)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val match = amountLine.matchEntire(line.trim()) ?: return@mapNotNull null
                val digits = match.groupValues[1].replace(".", "")
                val amount = digits.toLongOrNull() ?: return@mapNotNull null
                val merchant = match.groupValues[2].trim().trimEnd('.')
                if (amount <= 0L || merchant.isBlank()) null else amount
            }

        // Android may mirror the same content in text and bigText. Conflicting values
        // are rejected rather than guessing which amount is authoritative.
        val distinctAmounts = parsedLines.distinct()
        if (distinctAmounts.size != 1) return null
        return ParsedPaymentCandidate(
            amount = distinctAmounts.single(),
            transactionReference = null
        )
    }
}
