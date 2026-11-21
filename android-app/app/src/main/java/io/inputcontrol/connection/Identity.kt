package io.inputcontrol.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("wifi-identities-v1", Context.MODE_PRIVATE)
    private val alias = "input-control-noise-wrap-v1"

    // Non-exportable wrapping key. The X25519 private key is decrypted only for the native session.
    @Synchronized
    fun privateKey(): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = prefs.getString("identity", null)
        val key =
            (ks.getKey(alias, null) as? SecretKey)
                ?: run {
                    check(wrapped == null) { "Android Keystore identity unavailable" }
                    KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                        init(
                            KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                        generateKey()
                    }
                }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (wrapped != null) {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return cipher.doFinal(blob, 12, blob.size - 12)
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(
            prefs
                .edit()
                .putString(
                    "identity",
                    Base64.encodeToString(cipher.iv + cipher.doFinal(raw), Base64.NO_WRAP),
                )
                .commit()
        )
        return raw
    }

    fun peers(): List<Device> =
        prefs.all
            .filterKeys { it.startsWith("peer.") }
            .map { (k, v) ->
                val o = JSONObject(v as String)
                Device(
                    o.getString("name"),
                    o.getString("host"),
                    o.getInt("port"),
                    k.removePrefix("peer."),
                    nickname = prefs.getString("label." + k.removePrefix("peer."), "").orEmpty(),
                )
            }

    fun remember(d: Device) {
        require(d.pin.length == 64)
        val o = JSONObject().put("name", d.name).put("host", d.host).put("port", d.port)
        check(prefs.edit().putString("peer." + d.pin, o.toString()).commit()) {
            "Cannot save receiver identity"
        }
    }

    fun rename(pin: String, label: String) {
        require(prefs.contains("peer." + pin))
        prefs
            .edit()
            .apply {
                if (label.isEmpty()) remove("label." + pin) else putString("label." + pin, label)
            }
            .apply()
    }

    fun forget(pin: String) {
        check(prefs.edit().remove("peer." + pin).remove("label." + pin).commit())
    }
}
