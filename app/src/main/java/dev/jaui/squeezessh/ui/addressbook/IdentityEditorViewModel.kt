package dev.jaui.squeezessh.ui.addressbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.keys.SshKeyException
import dev.jaui.squeezessh.data.keys.SshKeyGenerator
import dev.jaui.squeezessh.data.keys.SshKeyType
import dev.jaui.squeezessh.data.repository.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class IdentityEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val identities: IdentityRepository,
    private val keyGenerator: SshKeyGenerator,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    private val identityId: Long = savedStateHandle["identityId"] ?: NEW

    private val _state = MutableStateFlow(IdentityEditorState())
    val state: StateFlow<IdentityEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val existing = identityId.takeIf { it != NEW }?.let { identities.findById(it) }
        _state.value = IdentityEditorState(
            isNew = existing == null,
            nickname = existing?.nickname.orEmpty(),
            username = existing?.username.orEmpty(),
            hasStoredPassword = existing?.hasPassword == true,
            hasStoredKey = existing?.hasPrivateKey == true,
            publicKeyLine = existing?.publicKey,
        )
    }

    fun onNicknameChanged(value: String) = _state.update { it.copy(nickname = value) }

    fun onUsernameChanged(value: String) = _state.update { it.copy(username = value) }

    fun onPasswordChanged(value: String) =
        _state.update { it.copy(password = value, passwordTouched = true) }

    fun clearPassword() =
        _state.update { it.copy(password = "", passwordTouched = true, hasStoredPassword = false) }

    /** Eingefuegter oder importierter Schluessel im PEM-Format. */
    fun onPrivateKeyPasted(pem: String) = _state.update {
        it.copy(
            privateKeyPem = pem,
            keyTouched = true,
            hasStoredKey = pem.isNotBlank(),
            publicKeyLine = null,
            generatedFingerprint = null,
        )
    }

    fun onKeyPassphraseChanged(value: String) =
        _state.update { it.copy(keyPassphrase = value, keyTouched = true) }

    fun clearKey() = _state.update {
        it.copy(
            privateKeyPem = "",
            keyPassphrase = "",
            keyTouched = true,
            hasStoredKey = false,
            publicKeyLine = null,
            generatedFingerprint = null,
        )
    }

    /**
     * Erzeugt ein neues Paar.
     *
     * Der private Teil wird erst beim Speichern in den Keystore ueberfuehrt; bis dahin
     * liegt er nur im Zustand dieses Bildschirms. Der oeffentliche Teil wird sofort
     * angezeigt, damit er sich auf den Server kopieren laesst.
     */
    fun generateKey(type: SshKeyType) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            try {
                val comment = _state.value.username.takeIf { it.isNotBlank() }
                    ?.let { "$it@squeezessh" }
                    ?: "squeezessh"
                val pair = withContext(io) { keyGenerator.generate(type, comment) }
                _state.update {
                    it.copy(
                        busy = false,
                        privateKeyPem = pair.privateKeyPem,
                        keyPassphrase = "",
                        keyTouched = true,
                        hasStoredKey = true,
                        publicKeyLine = pair.publicKeyLine,
                        generatedFingerprint = pair.fingerprint,
                    )
                }
            } catch (e: SshKeyException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Gibt den privaten Schluessel im Klartext heraus — ausschliesslich fuer den Export.
     *
     * Das ist die einzige Stelle, an der er den Vault verlaesst, und sie ist bewusst
     * ausdruecklich: ein Export ist eine Entscheidung des Nutzers, kein Nebeneffekt.
     */
    suspend fun exportablePrivateKey(): String? = withContext(io) {
        _state.value.privateKeyPem.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val existing = identityId.takeIf { it != NEW }?.let { identities.findById(it) }
            ?: return@withContext null
        identities.revealPrivateKey(existing)
    }

    /** @return `true`, wenn gespeichert wurde. */
    suspend fun save(): Boolean {
        val current = _state.value
        if (!current.canSave) return false

        if (current.isNew) {
            identities.create(
                nickname = current.nickname.takeIf { it.isNotBlank() },
                username = current.username.trim(),
                password = current.password.takeIf { current.passwordTouched && it.isNotBlank() },
                privateKey = current.privateKeyPem.takeIf { current.keyTouched && it.isNotBlank() },
                privateKeyPassword = current.keyPassphrase.takeIf {
                    current.keyTouched && it.isNotBlank()
                },
                publicKey = current.publicKeyLine,
            )
            return true
        }

        val existing = identities.findById(identityId) ?: return false
        identities.update(
            existing.copy(
                nickname = current.nickname.takeIf { it.isNotBlank() },
                username = current.username.trim(),
            ),
        )
        // Geheimnisse nur anfassen, wenn sie in diesem Bildschirm veraendert wurden —
        // sonst wuerde ein blosses Umbenennen das hinterlegte Passwort loeschen.
        if (current.passwordTouched) {
            identities.setPassword(identityId, current.password.takeIf { it.isNotBlank() })
        }
        if (current.keyTouched) {
            identities.setPrivateKey(
                identityId = identityId,
                privateKey = current.privateKeyPem.takeIf { it.isNotBlank() },
                privateKeyPassword = current.keyPassphrase.takeIf { it.isNotBlank() },
                publicKey = current.publicKeyLine,
            )
        }
        return true
    }

    companion object {
        const val NEW = -1L
    }
}

data class IdentityEditorState(
    val isNew: Boolean = true,
    val nickname: String = "",
    val username: String = "",
    val password: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    /** Ein bereits gespeichertes Geheimnis wird nie zurueckgelesen, nur angezeigt, dass es da ist. */
    val hasStoredPassword: Boolean = false,
    val hasStoredKey: Boolean = false,
    val publicKeyLine: String? = null,
    val generatedFingerprint: String? = null,
    val passwordTouched: Boolean = false,
    val keyTouched: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = username.isNotBlank() && !busy
}
