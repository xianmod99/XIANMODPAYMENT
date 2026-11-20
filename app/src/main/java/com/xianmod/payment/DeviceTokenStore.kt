package com.xianmod.payment

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the device bearer token encrypted with an Android Keystore AES key. */
object DeviceTokenStore {
    private const val PREFS = "xianmod_secure_device"
    private const val TOKEN = "device_token"
    private const val DEVICE_ID = "device_id"
    private const val KEY_ALIAS = "xianmod_payment_device_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Menyimpan token terenkripsi. Bila Keystore bermasalah, alias lama dihapus lalu dicoba sekali lagi.
     * Setelah ditulis (commit sinkron), token dibaca balik untuk memastikan benar-benar bisa dipakai.
     * Gagal => ApiException("token_not_saved") supaya UI tidak pernah menampilkan "berhasil" tanpa token yang valid.
     */
    @Synchronized
    fun save(context: Context, token: String, deviceId: String) {
        val packed = try {
            encrypt(token)
        } catch (_: Exception) {
            deleteKey()
            try { encrypt(token) } catch (e: Exception) { throw ApiException("token_not_saved", 0, e) }
        }
        val written = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, packed)
            .putString(DEVICE_ID, deviceId)
            .commit()
        val readBack = try { decrypt(packed) } catch (e: Exception) { throw ApiException("token_not_saved", 0, e) }
        if (!written || readBack != token) throw ApiException("token_not_saved")
    }

    @Synchronized
    fun token(context: Context): String? {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN, null) ?: return null
        return try {
            decrypt(packed)
        } catch (e: GeneralSecurityException) {
            // Kunci hilang/invalid atau ciphertext rusak: token tidak bisa dipulihkan.
            clear(context)
            deleteKey()
            null
        } catch (_: Exception) {
            // Kegagalan sementara (mis. Keystore sibuk): jangan hapus token, cukup anggap tidak tersedia kali ini.
            null
        }
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packedBase64: String): String? {
        val bytes = Base64.decode(packedBase64, Base64.NO_WRAP)
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }

    fun deviceId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(DEVICE_ID, null)

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Synchronized
    private fun deleteKey() {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Tidak ada tindakan aman lain; pairing berikutnya akan melaporkan error jika Keystore tetap gagal.
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
