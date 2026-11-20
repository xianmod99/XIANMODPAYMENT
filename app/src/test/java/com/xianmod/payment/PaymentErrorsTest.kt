package com.xianmod.payment

import org.junit.Assert.*
import org.junit.Test

class PaymentErrorsTest {
    @Test
    fun invalidCodeMessageMentionsNewCodeAndStatus() {
        val msg = PaymentErrors.describe(ApiException("code_invalid_or_expired", 401))
        assertTrue(msg.contains("kedaluwarsa"))
        assertTrue(msg.contains("HTTP 401"))
    }

    @Test
    fun serverUnavailableKeepsOriginal503() {
        val msg = PaymentErrors.describe(ApiException("rate_limiter_unavailable", 503))
        assertTrue(msg.contains("HTTP 503"))
    }

    @Test
    fun unknownHttpErrorIsNotDisguised() {
        val msg = PaymentErrors.describe(ApiException("http_502", 502))
        assertTrue(msg.contains("http_502"))
        assertTrue(msg.contains("HTTP 502"))
    }

    @Test
    fun networkErrorsHaveNoHttpStatus() {
        val msg = PaymentErrors.describe(ApiException("network_timeout", 0))
        assertFalse(msg.contains("HTTP"))
        assertTrue(msg.contains("batas waktu"))
    }

    @Test
    fun tokenNotSavedTellsUserToRequestNewCode() {
        assertTrue(PaymentErrors.describe(ApiException("token_not_saved")).contains("kode baru"))
    }

    @Test
    fun nonApiExceptionGivesGenericMessage() {
        assertTrue(PaymentErrors.describe(IllegalStateException("x")).contains("IllegalStateException"))
    }

    @Test
    fun approveErrorsAreExplained() {
        assertTrue(PaymentErrors.describe(ApiException("amount_mismatch", 409)).contains("Nominal"))
        assertTrue(PaymentErrors.describe(ApiException("order_not_pending", 409)).contains("PENDING"))
    }
}
