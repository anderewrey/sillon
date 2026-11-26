package io.github.inobody92.sillon.ui

import android.content.ClipboardManager
import android.content.Context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter

@Composable
fun LoginScreen(
    state: AuthState.LoggedOut,
    initialUrl: String,
    initialAccount: String,
    initialInsecure: Boolean,
    /** Mot de passe déjà enregistré : on propose de reprendre ce compte sans rien retaper (au plus un code OTP). */
    savedPassword: Boolean,
    onLogin: (url: String, account: String, password: String, insecure: Boolean, otp: String?) -> Unit,
    onConfirmCertificate: () -> Unit,
    onRejectCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var account by rememberSaveable { mutableStateOf(initialAccount) }
    var password by rememberSaveable { mutableStateOf("") }
    var insecure by rememberSaveable { mutableStateOf(initialInsecure) }
    var otp by rememberSaveable { mutableStateOf("") }
    // « Utiliser un autre compte » : on revient au formulaire complet.
    var fullForm by rememberSaveable { mutableStateOf(false) }
    val reconnect = savedPassword && !fullForm && initialUrl.isNotBlank() && initialAccount.isNotBlank()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sillon", style = MaterialTheme.typography.displayMedium)

        if (reconnect) {
            // Le compte est connu et son mot de passe enregistré : seul ce qui manque est demandé.
            Text(
                if (state.otpRequired) "Code de vérification" else "Reconnexion",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(initialAccount, style = MaterialTheme.typography.titleMedium)
            Text(
                initialUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.otpRequired) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Entre le code de ton application d'authentification. Ce téléphone sera ensuite mémorisé : " +
                        "le NAS ne devrait plus le redemander.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Diagnostic : distingue « jamais mémorisé » de « mémorisé mais refusé par le NAS ».
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.deviceRejected) {
                        "Le NAS n'a pas reconnu ce téléphone comme appareil de confiance."
                    } else {
                        "Ce téléphone n'est pas encore mémorisé par le NAS."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OtpField(otp, onChange = { otp = it }, autoFocus = true, onDone = {
                    if (otp.length >= 6) onLogin(initialUrl, initialAccount, "", initialInsecure, otp)
                })
            }
            state.error?.let { ErrorText(it) }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(initialUrl, initialAccount, "", initialInsecure, otp.ifBlank { null }) },
                enabled = !state.otpRequired || otp.length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.otpRequired) "Valider" else "Réessayer") }
            TextButton(onClick = { fullForm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Utiliser un autre compte")
            }
        } else {
            val submit = { onLogin(url, account, password, insecure, otp.ifBlank { null }) }
            Text(
                "Connecte-toi à ton NAS Synology",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adresse du NAS ou ID QuickConnect") },
                placeholder = { Text("monnas.synology.me:5001 ou mon-id") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Compte") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            // Toujours visible : avec la 2FA, le code part dès la première tentative (un code copié dans le
            // gestionnaire de mots de passe est déjà repris). Sans 2FA, on le laisse vide.
            Spacer(Modifier.height(12.dp))
            OtpField(
                otp,
                onChange = { otp = it },
                optional = !state.otpRequired,
                onDone = { if (url.isNotBlank() && account.isNotBlank() && password.isNotEmpty()) submit() },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Certificat auto-signé", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "À n'activer que si ton NAS n'a pas de certificat reconnu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = insecure, onCheckedChange = { insecure = it })
            }

            when {
                state.error != null -> ErrorText(state.error)
                state.otpRequired -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Ce compte demande un code de vérification.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = submit,
                enabled = url.isNotBlank() && account.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Se connecter") }
        }
    }

    state.certificate?.let { request ->
        AlertDialog(
            onDismissRequest = onRejectCertificate,
            title = { Text("Faire confiance à ce certificat ?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (request.previousSha256 != null) {
                        Text(
                            "ATTENTION : ce NAS présente un certificat DIFFÉRENT de celui que tu avais accepté. " +
                                "C'est normal si tu l'as renouvelé ; sinon, quelqu'un se fait peut-être passer pour ton NAS.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "Ce NAS utilise un certificat auto-signé. Compare l'empreinte ci-dessous avec celle affichée " +
                            "dans DSM (Panneau de configuration, Sécurité, Certificat) avant d'accepter.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Empreinte SHA-256", style = MaterialTheme.typography.labelMedium)
                    Text(request.info.formatted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Sujet : ${request.info.subject}", style = MaterialTheme.typography.bodySmall)
                    Text("Valable jusqu'au : ${request.info.notAfter}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = onConfirmCertificate) { Text("Faire confiance") } },
            dismissButton = { TextButton(onClick = onRejectCertificate) { Text("Annuler") } },
        )
    }
}

/**
 * Champ du code à deux facteurs. Deux façons de ne pas le taper :
 * - marqué comme code d'application d'authentification, pour que le remplissage automatique (Bitwarden et autres
 *   gestionnaires de mots de passe) puisse le proposer ;
 * - un code copié (6 à 8 chiffres) est repris du presse-papiers dès qu'on revient dans l'application : on copie le
 *   code dans Bitwarden, on revient, il est déjà là.
 */
@Composable
private fun OtpField(
    value: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    autoFocus: Boolean = false,
    /** Formulaire complet : le code n'est utile qu'aux comptes protégés par la double authentification. */
    optional: Boolean = false,
) {
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val focus = remember { FocusRequester() }
    // Dernier code repris du presse-papiers : on ne le remet pas une seconde fois (s'il a été refusé, par exemple).
    var pasted by rememberSaveable { mutableStateOf<String?>(null) }
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)

    fun takeFromClipboard() {
        val code = clipboardOtp(context) ?: return
        if (code != pasted && code != currentValue) {
            pasted = code
            currentOnChange(code)
        }
    }
    // Android ne laisse lire le presse-papiers qu'à l'application qui a le focus : on attend donc que la fenêtre
    // l'ait (au premier affichage comme au retour depuis Bitwarden).
    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.filter { it }.collect { takeFromClipboard() }
    }
    // Code copié sans quitter Sillon (bulle ou suggestion du gestionnaire de mots de passe, notification) : repris aussi.
    DisposableEffect(Unit) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { takeFromClipboard() }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }

    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(8)) },
        label = { Text(if (optional) "Code 2FA (si ton compte en a un)" else "Code à usage unique (2FA)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentType = ContentType(AUTOFILL_HINT_2FA_APP_OTP) },
    )
    if (pasted != null && value == pasted) {
        Text(
            "Code repris du presse-papiers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
    }
}

/** Code à deux facteurs dans le presse-papiers (6 à 8 chiffres, espaces tolérés : « 123 456 »), sinon null. */
private fun clipboardOtp(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
    text.filterNot(Char::isWhitespace).takeIf { OTP_PATTERN.matches(it) }
}.getOrNull()

private val OTP_PATTERN = Regex("^\\d{6,8}$")

/** `View.AUTOFILL_HINT_2FA_APP_OTP` (Android 13) : code d'une application d'authentification, pas d'un SMS. */
private const val AUTOFILL_HINT_2FA_APP_OTP = "2faAppOTPCode"

@Composable
private fun ErrorText(message: String) {
    Spacer(Modifier.height(12.dp))
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
