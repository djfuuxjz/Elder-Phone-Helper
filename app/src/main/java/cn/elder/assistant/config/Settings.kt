package cn.elder.assistant.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("model_config", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.deepseek.com")!!
        set(value) { prefs.edit().putString("endpoint",value.trim().trimEnd('/')).apply() }
    var model: String
        get() = prefs.getString("model", "deepseek-flash")!!
        set(value) { prefs.edit().putString("model",value.trim()).apply() }
    var vision: Boolean
        get() = prefs.getBoolean("vision",true)
        set(value) { prefs.edit().putBoolean("vision",value).apply() }
    var autoGui:Boolean
        get()=prefs.getBoolean("auto_gui",false)
        set(value) { prefs.edit().putBoolean("auto_gui",value).apply() }
    fun hasKey() = prefs.contains("encrypted_key")
    fun apiKey(): String {
        val stored=prefs.getString("encrypted_key", null) ?: return ""
        val parts=stored.split(":")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
    fun saveKey(value: String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,secret())
        val data=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.trim().toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted_key",data).commit()) { "无法保存 API 密钥" }
    }
    fun clearKey() { prefs.edit().remove("encrypted_key").apply() }
    private fun secret(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("assistant_api",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("assistant_api",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
