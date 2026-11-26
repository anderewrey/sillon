package io.github.inobody92.sillon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stocke les identifiants. Le mot de passe est chiffré en AES-GCM avec une clé
 * non exportable du Keystore Android ; le reste n'est pas sensible.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sillon_session", Context.MODE_PRIVATE)

    fun save(c: Credentials) {
        prefs.edit()
            .putString(K_URL, c.url)
            .putString(K_ACCOUNT, c.account)
            .putString(K_PASSWORD, encrypt(c.password))
            .putBoolean(K_INSECURE, c.insecure)
            .putString(K_DEVICE_ENC, c.deviceId?.let(::encrypt))
            .remove(K_DEVICE)
            .putString(K_PIN, c.pinnedSha256)
            .putString(K_QC, c.quickConnectId)
            .apply()
    }

    fun load(): Credentials? {
        val url = prefs.getString(K_URL, null) ?: return null
        val account = prefs.getString(K_ACCOUNT, null) ?: return null
        val enc = prefs.getString(K_PASSWORD, null) ?: return null
        val password = runCatching { decrypt(enc) }.getOrNull() ?: return null
        return Credentials(url, account, password, prefs.getBoolean(K_INSECURE, false), loadDeviceId(),
            prefs.getString(K_PIN, null), prefs.getString(K_QC, null),
        )
    }

    /**
     * Device de confiance (permet de se connecter sans code 2FA) : chiffré comme le mot de passe. Les versions
     * précédentes l'enregistraient en clair : on le reprend une fois, puis on le réenregistre chiffré.
     */
    private fun loadDeviceId(): String? {
        prefs.getString(K_DEVICE_ENC, null)?.let { enc -> return runCatching { decrypt(enc) }.getOrNull() }
        val legacy = prefs.getString(K_DEVICE, null) ?: return null
        prefs.edit().putString(K_DEVICE_ENC, encrypt(legacy)).remove(K_DEVICE).apply()
        return legacy
    }

    /** Session du NAS (identifiant de session chiffré), pour s'ouvrir sans attendre le réseau. [owner] = adresse + compte. */
    fun saveSession(owner: String, snapshot: SessionSnapshot) {
        val json = org.json.JSONObject()
            .put("owner", owner)
            .put("sid", snapshot.sid)
            .put("transcode", org.json.JSONArray(snapshot.transcodeFormats.toList()))
            .put("apis", org.json.JSONObject().also { o ->
                snapshot.apis.forEach { (name, i) ->
                    o.put(name, org.json.JSONArray().put(i.path).put(i.minVersion).put(i.maxVersion))
                }
            })
        prefs.edit().putString(K_SESSION, encrypt(json.toString())).apply()
    }

    fun loadSession(owner: String): SessionSnapshot? = runCatching {
        val json = org.json.JSONObject(decrypt(prefs.getString(K_SESSION, null) ?: return null))
        if (json.getString("owner") != owner) return null
        val apis = json.getJSONObject("apis")
        val transcode = json.getJSONArray("transcode")
        SessionSnapshot(
            json.getString("sid"),
            apis.keys().asSequence().associateWith { k -> apis.getJSONArray(k).let { ApiInfo(it.getString(0), it.getInt(1), it.getInt(2)) } },
            List(transcode.length()) { transcode.getString(it) }.toSet(),
        )
    }.getOrNull()

    /** Dernières valeurs saisies, pour préremplir l'écran de connexion (sans mot de passe). */
    fun lastUrl(): String = prefs.getString(K_URL, "") ?: ""
    fun lastAccount(): String = prefs.getString(K_ACCOUNT, "") ?: ""
    fun lastInsecure(): Boolean = prefs.getBoolean(K_INSECURE, false)

    /** Serveur de téléchargement (facultatif) : adresse, jeton chiffré, empreinte du certificat épinglé. */
    fun saveDownloadServer(c: DownloadServerConfig) {
        prefs.edit()
            .putString(K_DS_URL, c.url)
            .putString(K_DS_TOKEN, encrypt(c.token))
            .putString(K_DS_PIN, c.pinnedSha256)
            .apply()
    }

    fun loadDownloadServer(): DownloadServerConfig? {
        val url = prefs.getString(K_DS_URL, null) ?: return null
        val token = prefs.getString(K_DS_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return DownloadServerConfig(url, token, prefs.getString(K_DS_PIN, null))
    }

    fun clearDownloadServer() {
        prefs.edit().remove(K_DS_URL).remove(K_DS_TOKEN).remove(K_DS_PIN).apply()
    }

    /** Oublie le mot de passe mais garde l'adresse, le compte et le device de confiance. */
    fun forgetPassword() {
        prefs.edit().remove(K_PASSWORD).remove(K_SESSION).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "sillon_credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val K_URL = "url"
        const val K_ACCOUNT = "account"
        const val K_PASSWORD = "password"
        const val K_INSECURE = "insecure"
        const val K_DEVICE = "device_id" // ancien format, en clair
        const val K_DEVICE_ENC = "device_id_enc"
        const val K_PIN = "pinned_sha256"
        const val K_QC = "quickconnect_id"
        const val K_SESSION = "nas_session"
        const val K_DS_URL = "download_server_url"
        const val K_DS_TOKEN = "download_server_token"
        const val K_DS_PIN = "download_server_pin"
    }
}
