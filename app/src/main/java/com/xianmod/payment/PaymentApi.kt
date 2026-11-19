package com.xianmod.payment

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object PaymentApi {
    data class PendingOrder(
        val orderId: String,
        val packageName: String,
        val amount: Long,
        val expiresAt: String,
        val createdAt: String
    )

    data class PairResult(val deviceId: String, val token: String)

    fun pair(context: Context, code: String): PairResult {
        val body = JSONObject()
            .put("code", code)
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
            .put("model", Build.MODEL.take(60))
            .put("app_version", BuildConfig.VERSION_NAME)
        val result = request("POST", "/api/payment/listener/pair", null, body)
        if (!result.optBoolean("ok")) throw ApiException(result.optString("error", "pair_failed"))
        return PairResult(result.getString("device_id"), result.getString("token"))
    }

    fun fetchOrders(context: Context): List<PendingOrder> {
        val token = DeviceTokenStore.token(context) ?: throw ApiException("device_not_paired")
        val result = request("GET", "/api/payment/listener/orders", token)
        if (!result.optBoolean("ok")) throw ApiException(result.optString("error", "orders_failed"))
        val items = result.optJSONArray("orders") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            PendingOrder(
                orderId = item.optString("order_id"),
                packageName = item.optString("package_name"),
                amount = item.optDouble("total_amount_idr", 0.0).toLong(),
                expiresAt = item.optString("qr_expires_at"),
                createdAt = item.optString("created_at")
            )
        }
    }

    fun heartbeat(context: Context, listenerConnected: Boolean) {
        val token = DeviceTokenStore.token(context) ?: return
        val body = JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("listener_connected", listenerConnected)
            .put("queue_depth", 0)
            .put("model", Build.MODEL)
        request("POST", "/api/payment/listener/heartbeat", token, body)
    }

    fun postCandidate(context: Context, packageName: String, amount: Long, reference: String?, postedAt: Long) {
        val token = DeviceTokenStore.token(context) ?: return
        val body = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("package_name", packageName)
            .put("amount", amount)
            .put("reference", reference ?: JSONObject.NULL)
            .put("posted_at", postedAt)
            .put("parsed", true)
            .put("app_version", BuildConfig.VERSION_NAME)
        request("POST", "/api/payment/listener/event", token, body)
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject? = null): JSONObject {
        val connection = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("error", "invalid_server_response") }
            if (code !in 200..299) throw ApiException(json.optString("error", "http_$code"))
            return json
        } finally {
            connection.disconnect()
        }
    }

    class ApiException(message: String) : Exception(message)
}
