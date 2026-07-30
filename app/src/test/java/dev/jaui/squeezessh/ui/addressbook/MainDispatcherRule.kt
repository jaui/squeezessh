package dev.jaui.squeezessh.ui.addressbook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Stellt einen Main-Dispatcher für Tests bereit.
 *
 * Ein ViewModel teilt seinen Zustand über `stateIn(viewModelScope, …)`, und `viewModelScope`
 * läuft auf [Dispatchers.Main]. Ohne Ersatz startet die teilende Coroutine im Test nie und
 * `first { … }` wartet ewig. `UnconfinedTestDispatcher`, damit sie sofort anläuft statt erst
 * beim nächsten Vorspulen der Uhr.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
