package com.xianmod.payment

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
    data class ApproveResult(val already: Boolean, val fulfillmentStatus: String?, val fulfillmentOk: Boolean, val fulfillmentError: String?)

    /** Approve manual: server memeriksa ulang nominal, status PENDING, dan metode GoPay sebelum menandai PAID. */
    fun approveOrder(context: Context, orderId: String, amount: Long): ApproveResult {
        val token = DeviceTokenStore.token(context) ?: throw ApiException("device_not_paired")
        val body = JSONObject().put("order_id", orderId).put("confirm_amount", amount)
        val result = request("POST", "/api/payment/listener/approve-order", token, body)
        if (!result.optBoolean("ok")) throw ApiException(result.optString("error", "approve_failed"))
        return ApproveResult(
            already = result.optBoolean("already"),
            fulfillmentStatus = result.optString("fulfillment_status").ifBlank { null },
            fulfillmentOk = result.optBoolean("fulfillment_ok"),
            fulfillmentError = result.optString("fulfillment_error").ifBlank { null }
        )
    }

    fun pair(context: Context, code: String): PairResult {
        val body = JSONObject()
            .put("code", code)
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
            .put("model", Build.MODEL.take(60))
            .put("app_version", BuildConfig.VERSION_NAME)
        val result = request("POST", "/api/payment/listener/pair", null, body)
        if (!result.optBoolean("ok")) throw ApiException(result.optString("error", "pair_failed"))
        val deviceId = result.optString("device_id")
        val token = result.optString("token")
        if (deviceId.isBlank() || token.isBlank()) throw ApiException("pair_response_incomplete")
        return PairResult(deviceId, token)
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
        var status = 0
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12000
                readTimeout = 15000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            connection = conn
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (status !in 200..299) {
                // Kode error dari backend dipertahankan apa adanya; status HTTP asli selalu ikut.
                val code = json?.optString("error").orEmpty().ifBlank { if (json == null) "invalid_server_response" else "http_$status" }
                throw ApiException(code, status)
            }
            return json ?: throw ApiException("invalid_server_response", status)
        } catch (e: ApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ApiException("network_dns", status, e)
        } catch (e: SocketTimeoutException) {
            throw ApiException("network_timeout", status, e)
        } catch (e: ConnectException) {
            throw ApiException("network_connect", status, e)
        } catch (e: SSLException) {
            throw ApiException("network_ssl", status, e)
        } catch (e: IOException) {
            throw ApiException("network_io", status, e)
        } finally {
            connection?.disconnect()
        }
    }
}
