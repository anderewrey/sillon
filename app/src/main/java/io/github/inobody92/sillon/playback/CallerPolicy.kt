package io.github.inobody92.sillon.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.security.MessageDigest

/** Qui se connecte au service de lecture. */
enum class CallerClass {
    /** Sillon lui-même (interface, notification Media3). */
    SELF,

    /** Le système, ou une application qu'Android autorise à contrôler les médias (MEDIA_CONTENT_CONTROL, écouteur de notifications). */
    TRUSTED,

    /** Une application connue (Android Auto, Assistant, Wear OS), vérifiée par son certificat de signature. */
    KNOWN,

    /** Toute autre application. */
    UNKNOWN,
}

/**
 * Classe les applications qui se connectent au service de lecture.
 *
 * Tout se décide par l'UID, fourni par Android : le nom de paquet d'un contrôleur Media3 est déclaré par l'appelant
 * lui-même, et les aides de Media3 (`isAutoCompanionController`…) ne font que comparer ce nom.
 */
@UnstableApi
class CallerPolicy(private val context: Context) {

    fun classify(controller: MediaSession.ControllerInfo): CallerClass {
        val uid = controller.uid
        return when {
            uid == Process.myUid() -> CallerClass.SELF
            controller.isTrusted -> CallerClass.TRUSTED
            uid >= 0 && isKnown(uid) -> CallerClass.KNOWN
            else -> CallerClass.UNKNOWN
        }
    }

    private fun isKnown(uid: Int): Boolean {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return false
        return packages.any { pkg -> KnownCallers.KEYS[pkg]?.any { hasCertificate(pm, pkg, it) } == true }
    }

    private fun hasCertificate(pm: PackageManager, pkg: String, sha256Hex: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // Tient compte de la rotation de clé (APK Signature Scheme v3).
            pm.hasSigningCertificate(pkg, KnownCallers.bytes(sha256Hex), PackageManager.CERT_INPUT_SHA256)
        } else {
            @Suppress("DEPRECATION")
            val signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            val only = signatures?.singleOrNull() ?: return false
            KnownCallers.hex(MessageDigest.getInstance("SHA-256").digest(only.toByteArray())) == sha256Hex
        }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Applications connues et empreintes SHA-256 de leur certificat de signature de production.
 * Source : la liste d'appelants autorisés de l'exemple de Google UAMP
 * (common/src/main/res/xml/allowed_media_browser_callers.xml, commit 9498d991), clés `release="true"`.
 */
object KnownCallers {
    val KEYS: Map<String, Set<String>> = mapOf(
        // Android Auto : clé de production et clé après rotation
        "com.google.android.projection.gearhead" to setOf(
            "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
            "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295",
        ),
        // Wear OS
        "com.google.android.wearable.app" to setOf(
            "85cd5973541be6f477d847a0bcc6aa2527684b819cd5968529664cb07157b6fe",
        ),
        // Application Google (Assistant)
        "com.google.android.googlequicksearchbox" to setOf(
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
        ),
        // Assistant sur Android Automotive OS
        "com.google.android.carassistant" to setOf(
            "74b6fbf710e8d90d44d340125889b42306a62c4379d0e5a66220e3a68abf90e2",
        ),
    )

    fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
