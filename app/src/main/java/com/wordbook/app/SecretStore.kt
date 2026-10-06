package com.wordbook.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储：密钥保存在 Android Keystore（系统级，不可导出），
 * Key 以 AES-GCM 密文写入 SharedPreferences，解密仅在内存中进行。
 * 存储格式 v1: Base64(iv):Base64(密文)。
 */
object SecretStore {

    private const val KEY_ALIAS = "wordbook_api_key_alias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** 加密并返回可存储的字符串；空串直接返回 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(enc, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 加密失败时退回原值（极少数厂商 Keystore 异常场景，避免用户无法使用）
            plain
        }
    }

    /** 解密存储值；非加密格式（旧明文）原样返回，由调用方迁移 */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith("v1:")) return stored
        return try {
            val parts = stored.split(":")
            if (parts.size < 3) return stored
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val enc = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            stored
        }
    }
}
