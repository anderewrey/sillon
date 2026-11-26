package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    state: AuthState.LoggedOut,
    initialUrl: String,
    initialAccount: String,
    initialInsecure: Boolean,
    /** Mot de passe déjà enregistré : le champ peut rester vide (il suffit alors du code OTP, ou de réessayer). */
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sillon", style = MaterialTheme.typography.displayMedium)
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
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            placeholder = { if (savedPassword) Text("Enregistré : laisse vide pour le garder") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.otpRequired) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it.filter(Char::isDigit).take(8) },
                label = { Text("Code à usage unique (2FA)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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

        val message = when {
            state.error != null -> state.error
            state.otpRequired -> "Ce compte demande un code de vérification."
            else -> null
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(url, account, password, insecure, otp.ifBlank { null }) },
            enabled = url.isNotBlank() && account.isNotBlank() && (password.isNotEmpty() || savedPassword),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Se connecter") }
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
