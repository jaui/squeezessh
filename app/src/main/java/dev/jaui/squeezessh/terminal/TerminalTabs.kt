package dev.jaui.squeezessh.terminal

import android.content.Context
import dev.jaui.squeezessh.ssh.HostKeyPrompt
import dev.jaui.squeezessh.ssh.SshConnectionState
import dev.jaui.squeezessh.ssh.SshSession
import dev.jaui.squeezessh.ssh.SshSessionManager
import dev.jaui.squeezessh.ssh.SshSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die offenen Terminal-Tabs.
 *
 * Liegt bewusst ausserhalb der Oberflaeche: ein Tab-Wechsel oder eine Drehung darf keine
 * Sitzung kappen, und der Emulator eines Tabs muss auch dann weiter Ausgabe entgegennehmen,
 * wenn er gerade nicht sichtbar ist. Genau deshalb haelt diese Klasse den [TerminalBridge]
 * und nicht das ViewModel.
 */
@Singleton
class TerminalTabs @Inject constructor(
    private val sessions: SshSessionManager,
) {

    /**
     * Ein Tab.
     *
     * [onScreenUpdated] setzt immer nur der gerade sichtbare Tab. Die uebrigen sammeln
     * Ausgabe im Emulator, ohne Neuzeichnungen anzustossen.
     */
    class Tab internal constructor(
        val id: Long,
        val connectionId: Long,
        val session: SshSession,
        val bridge: TerminalBridge,
        initialTitle: String,
    ) {
        private val _title = MutableStateFlow(initialTitle)
        val title: StateFlow<String> = _title.asStateFlow()

        private val _isAlive = MutableStateFlow(true)
        val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

        private val _endedReason = MutableStateFlow<String?>(null)
        val endedReason: StateFlow<String?> = _endedReason.asStateFlow()

        @Volatile var onScreenUpdated: (() -> Unit)? = null

        @Volatile var onBell: (() -> Unit)? = null

        @Volatile var onClipboardWrite: ((String) -> Unit)? = null

        @Volatile var onClipboardRead: (() -> String?)? = null

        internal fun updateTitle(title: String?) {
            title?.takeIf { it.isNotBlank() }?.let { _title.value = it }
        }

        internal fun markEnded(reason: String?) {
            _isAlive.value = false
            _endedReason.value = reason
        }
    }

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<Long?>(null)
    val activeTabId: StateFlow<Long?> = _activeTabId.asStateFlow()

    private val lock = Mutex()

    val activeTab: Tab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    /**
     * Baut eine neue Sitzung auf und legt sie als Tab an.
     *
     * Groesse: bis die View gemessen ist, gelten 80×24. Sobald sie ihre Masse kennt,
     * meldet sie eine Korrektur — der Server bekommt sie dann sofort.
     */
    suspend fun open(
        context: Context,
        connectionId: Long,
        onHostKey: suspend (HostKeyPrompt) -> Boolean,
        onProgress: (SshConnectionState) -> Unit = {},
    ): Tab {
        val session = sessions.open(
            connectionId = connectionId,
            onHostKey = onHostKey,
            onProgress = onProgress,
        )

        lateinit var tab: Tab
        val bridge = TerminalBridge(
            shell = session.shell,
            columns = SshSessionManager.DEFAULT_COLUMNS,
            rows = SshSessionManager.DEFAULT_ROWS,
            cellWidthPixels = 0,
            cellHeightPixels = 0,
            listener = object : TerminalBridge.Listener {
                override fun onScreenUpdated() { tab.onScreenUpdated?.invoke() }
                override fun onTitleChanged(title: String?) = tab.updateTitle(title)
                override fun onBell() { tab.onBell?.invoke() }
                override fun onCopyToClipboard(text: String) { tab.onClipboardWrite?.invoke(text) }
                override fun onPasteRequested() {
                    tab.onClipboardRead?.invoke()?.let(tab.bridge::send)
                }
                override fun onFinished(reason: String?) = tab.markEnded(reason)
            },
        )

        tab = Tab(
            id = session.id,
            connectionId = connectionId,
            session = session,
            bridge = bridge,
            initialTitle = session.title,
        )

        lock.withLock {
            _tabs.update { it + tab }
            _activeTabId.value = tab.id
        }
        SshSessionService.start(context)
        return tab
    }

    fun activate(tabId: Long) {
        if (_tabs.value.any { it.id == tabId }) _activeTabId.value = tabId
    }

    suspend fun close(context: Context, tabId: Long) {
        lock.withLock {
            val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
            tab.onScreenUpdated = null
            tab.bridge.close()
            sessions.close(tabId)

            val remaining = _tabs.value.filterNot { it.id == tabId }
            _tabs.value = remaining
            if (_activeTabId.value == tabId) {
                _activeTabId.value = remaining.lastOrNull()?.id
            }
        }
        if (_tabs.value.isEmpty()) SshSessionService.stop(context)
    }

    suspend fun closeAll(context: Context) {
        lock.withLock {
            _tabs.value.forEach {
                it.onScreenUpdated = null
                it.bridge.close()
            }
            sessions.closeAll()
            _tabs.value = emptyList()
            _activeTabId.value = null
        }
        SshSessionService.stop(context)
    }
}
