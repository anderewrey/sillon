package io.github.inobody92.sillon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.ApiException
import io.github.inobody92.sillon.data.CertificateInfo
import io.github.inobody92.sillon.data.Credentials
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.OtpRequiredException
import io.github.inobody92.sillon.data.QuickConnect
import io.github.inobody92.sillon.data.SecureStore
import io.github.inobody92.sillon.data.SessionSnapshot
import io.github.inobody92.sillon.data.SessionHolder
import io.github.inobody92.sillon.data.SynoClient
import io.github.inobody92.sillon.data.probeCertificate
import io.github.inobody92.sillon.data.sessionOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLException

/** [previousSha256] : empreinte déjà acceptée pour ce NAS, si elle existe (et qui a donc changé). */
data class CertificateRequest(val info: CertificateInfo, val previousSha256: String?)

sealed interface AuthState {
    data object Loading : AuthState
    data class LoggedOut(
        val error: String? = null,
        val otpRequired: Boolean = false,
        /** Le code est redemandé alors qu'un appareil de confiance était enregistré : le NAS ne l'a pas reconnu. */
        val deviceRejected: Boolean = false,
        /** Certificat auto-signé à confirmer par l'utilisateur avant de continuer. */
        val certificate: CertificateRequest? = null,
    ) : AuthState

    /** NAS injoignable alors que des titres sont téléchargés : on propose l'écoute hors-ligne. */
    data object Offline : AuthState
    data class LoggedIn(val client: SynoClient) : AuthState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val lastUrl get() = store.lastUrl()
    val lastAccount get() = store.lastAccount()
    val lastInsecure get() = store.lastInsecure()

    /** Mot de passe encore enregistré : l'écran de connexion propose de reprendre ce compte (au plus un code OTP). */
    val hasSavedPassword get() = store.hasPassword()

    init {
        OfflineStore.init(app)
        val saved = store.load()
        if (saved == null) {
            _state.value = AuthState.LoggedOut()
        } else {
            val snapshot = store.loadSession(sessionOwner(saved))
                ?.takeIf { !(saved.insecure && saved.pinnedSha256 == null) }
            if (snapshot != null) connectFast(saved, snapshot) else connect(saved, otp = null, silent = true)
        }
    }

    /**
     * Lancement rapide : on rouvre l'application sur la dernière session connue, sans attendre le NAS (trois
     * requêtes à la suite sinon), puis on la revalide en arrière-plan. Si elle a expiré, le premier appel se
     * reconnecte tout seul.
     */
    private fun connectFast(creds: Credentials, snapshot: SessionSnapshot) {
        val live = SynoClient(creds, store::saveDeviceId).also { it.adopt(snapshot) }
        SessionHolder.client = live
        _state.value = AuthState.LoggedIn(live)
        viewModelScope.launch {
            try {
                val fresh = establish(creds, null)
                val fresh0 = fresh.snapshot()
                if (fresh.creds.url == live.creds.url && fresh0 != null) {
                    live.adopt(fresh0)
                } else {
                    // QuickConnect : le NAS a changé d'adresse, on bascule sur le nouveau client.
                    SessionHolder.client = fresh
                    _state.value = AuthState.LoggedIn(fresh)
                }
            } catch (_: OtpRequiredException) {
                SessionHolder.client = null
                _state.value = AuthState.LoggedOut(otpRequired = true, deviceRejected = creds.deviceId != null)
            } catch (e: Exception) {
                if (mustLogIn(e)) {
                    // Mot de passe refusé, compte désactivé, certificat changé : là, il faut l'utilisateur.
                    forgetRejectedPassword(e, creds)
                    SessionHolder.client = null
                    _state.value = AuthState.LoggedOut(error = describe(e))
                } else if (e is IOException && e !is SSLException && OfflineStore.state.value.songs.isNotEmpty()) {
                    // Pas de réseau : l'écran hors-ligne, puisqu'il y a des titres sur le téléphone.
                    _state.value = AuthState.Offline
                }
                // Sinon (réseau qui flanche, réponse inattendue…) on garde la session : elle est peut-être encore
                // valide, et le premier appel se reconnectera tout seul. Jamais d'écran de connexion pour un aléa.
            }
        }
    }

    /** Connexion complète (découverte, authentification, capacités) ; retrouve l'adresse QuickConnect si elle a changé. */
    private suspend fun establish(initial: Credentials, otp: String?): SynoClient {
        var creds = initial
        var client = SynoClient(creds, store::saveDeviceId)
        try {
            client.connect(otp)
        } catch (e: IOException) {
            // QuickConnect : l'adresse du NAS a pu changer (IP, port). On la retrouve une fois, puis on réessaie.
            val id = creds.quickConnectId
            if (id == null || e is SSLException) throw e
            val fresh = QuickConnect.resolve(id)
            if (fresh == null || fresh == creds.url) throw e
            creds = creds.copy(url = fresh)
            client = SynoClient(creds, store::saveDeviceId)
            client.connect(otp)
        }
        val saved = creds.copy(deviceId = client.deviceId)
        store.save(saved)
        client.snapshot()?.let { store.saveSession(sessionOwner(saved), it) }
        return client
    }

    private var pendingCertificate: Triple<Credentials, String?, CertificateRequest>? = null

    /**
     * [url] est une adresse, ou un ID QuickConnect (un mot sans point ni deux-points). Pour un NAS à certificat
     * auto-signé, la connexion attend que l'utilisateur ait confirmé l'empreinte du certificat.
     */
    fun login(url: String, account: String, password: String, insecure: Boolean, otp: String?) {
        val input = url.trim()
        _state.value = AuthState.Loading
        viewModelScope.launch {
            var quickConnectId: String? = null
            val base = if (QuickConnect.looksLikeId(input)) {
                quickConnectId = input
                QuickConnect.resolve(input) ?: run {
                    _state.value = AuthState.LoggedOut(
                        error = "QuickConnect : « $input » est introuvable ou injoignable. Vérifie l'ID, ou utilise l'adresse du NAS.",
                    )
                    return@launch
                }
            } else {
                try {
                    SynoClient.normalizeUrl(input)
                } catch (e: IllegalArgumentException) {
                    _state.value = AuthState.LoggedOut(error = e.message)
                    return@launch
                }
            }
            // On réutilise le device de confiance enregistré pour ce même compte et ce même NAS, même après une
            // déconnexion ou un changement d'adresse QuickConnect : sinon le NAS redemande un code OTP.
            val deviceId = store.trustedDevice(base, quickConnectId, account)
            // Écran de reconnexion (aucun mot de passe saisi) : celui enregistré pour ce compte.
            val pass = password.ifEmpty { store.savedPassword(base, quickConnectId, account) } ?: run {
                _state.value = AuthState.LoggedOut(error = "Saisis ton mot de passe")
                return@launch
            }
            // QuickConnect fournit un certificat valide : l'option « auto-signé » ne s'applique qu'à une adresse directe.
            val creds = Credentials(base, account, pass, insecure && quickConnectId == null, deviceId, null, quickConnectId)
            if (creds.insecure) askToTrustCertificate(creds, otp?.ifBlank { null }) else connect(creds, otp?.ifBlank { null }, silent = false)
        }
    }

    private suspend fun askToTrustCertificate(creds: Credentials, otp: String?) {
        val info = try {
            probeCertificate(creds.url)
        } catch (e: Exception) {
            _state.value = AuthState.LoggedOut(error = "Impossible de lire le certificat du NAS : ${e.message ?: "erreur réseau"}")
            return
        }
        val known = store.load()?.takeIf { it.url == creds.url }?.pinnedSha256
        if (known != null && known.equals(info.sha256, ignoreCase = true)) {
            // Même certificat que celui déjà accepté : inutile de reposer la question.
            connect(creds.copy(pinnedSha256 = known), otp, silent = false)
            return
        }
        val request = CertificateRequest(info, previousSha256 = known)
        pendingCertificate = Triple(creds, otp, request)
        _state.value = AuthState.LoggedOut(certificate = request)
    }

    fun confirmCertificate() {
        val (creds, otp, request) = pendingCertificate ?: return
        pendingCertificate = null
        connect(creds.copy(pinnedSha256 = request.info.sha256), otp, silent = false)
    }

    fun rejectCertificate() {
        pendingCertificate = null
        _state.value = AuthState.LoggedOut()
    }

    /** Depuis l'écran hors-ligne : retente la connexion avec les identifiants enregistrés. */
    fun retryConnection() {
        store.load()?.let { connect(it, otp = null, silent = true) } ?: run { _state.value = AuthState.LoggedOut() }
    }

    fun logout() {
        val current = (_state.value as? AuthState.LoggedIn)?.client
        store.forgetPassword()
        SessionHolder.client = null
        _state.value = AuthState.LoggedOut()
        if (current != null) viewModelScope.launch { current.logout() }
    }

    private fun connect(initial: Credentials, otp: String?, silent: Boolean) {
        // Réglage hérité d'une ancienne version (confiance aveugle) : on exige de confirmer le certificat.
        if (initial.insecure && initial.pinnedSha256 == null) {
            _state.value = AuthState.LoggedOut(
                error = "Ce NAS utilise un certificat auto-signé : reconnecte-toi pour en confirmer l'empreinte.",
            )
            return
        }
        _state.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val client = establish(initial, otp)
                SessionHolder.client = client
                _state.value = AuthState.LoggedIn(client)
            } catch (_: OtpRequiredException) {
                _state.value = AuthState.LoggedOut(otpRequired = true, deviceRejected = initial.deviceId != null)
            } catch (e: Exception) {
                // Reconnexion automatique impossible faute de réseau : si des titres sont sur le téléphone, on les propose.
                forgetRejectedPassword(e, initial)
                _state.value = if (silent && !mustLogIn(e) && OfflineStore.state.value.songs.isNotEmpty()) {
                    AuthState.Offline
                } else if (e is ApiException && e.code == ERROR_BAD_OTP) {
                    // Code erroné : on reste sur la saisie du code.
                    AuthState.LoggedOut(error = describe(e), otpRequired = true)
                } else {
                    // Toujours dire pourquoi : « NAS injoignable » n'appelle pas la même réaction qu'un mot de passe
                    // refusé. Le mot de passe reste enregistré, « Se connecter » suffit pour réessayer.
                    AuthState.LoggedOut(error = describe(e))
                }
            }
        }
    }

    /**
     * Erreur qui demande vraiment l'utilisateur : identifiants refusés, compte désactivé ou certificat changé.
     * Tout le reste (réseau, NAS lent, réponse inattendue) est passager et ne doit pas déconnecter.
     */
    private fun mustLogIn(e: Exception): Boolean =
        (e is ApiException && e.code in CREDENTIAL_ERRORS) || certificateChanged(e)

    /**
     * Le NAS refuse le mot de passe enregistré (changé dans DSM) : on l'oublie, pour que l'écran de connexion
     * redemande le mot de passe au lieu de proposer une reconnexion vouée à l'échec. Le device de confiance reste.
     */
    private fun forgetRejectedPassword(e: Exception, used: Credentials) {
        if (e is ApiException && e.code == ERROR_BAD_PASSWORD && store.load()?.password == used.password) {
            store.forgetPassword()
        }
    }

    private fun certificateChanged(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("a changé") == true }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            400 -> "Identifiants incorrects"
            401 -> "Ce compte est désactivé"
            402 -> "Permission refusée"
            404 -> "Code OTP incorrect"
            406 -> "L'authentification en deux étapes est obligatoire sur ce compte"
            else -> e.message ?: "Erreur ${e.code}"
        }
        is SSLException ->
            if (certificateChanged(e)) {
                "Le certificat du NAS n'est plus celui que tu avais accepté. Si ce changement est normal, reconnecte-toi pour le confirmer."
            } else {
                "Certificat non reconnu. S'il est auto-signé, active l'option dédiée."
            }
        is IOException -> "Impossible de joindre le NAS : vérifie l'adresse, le port et ta connexion"
        else -> e.message ?: "Erreur inconnue"
    }

    private companion object {
        /** Codes de `SYNO.API.Auth` login qui tiennent au compte : 400 identifiants, 401 désactivé, 402 refusé, 406 2FA à configurer. */
        val CREDENTIAL_ERRORS = setOf(400, 401, 402, 406)
        const val ERROR_BAD_PASSWORD = 400
        const val ERROR_BAD_OTP = 404
    }
}
