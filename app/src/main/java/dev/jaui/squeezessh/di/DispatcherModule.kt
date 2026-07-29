package dev.jaui.squeezessh.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /**
     * Dispatcher fuer blockierende Arbeit (Datei- und Datenbankzugriff, SSH-Sockets).
     * Als Abhaengigkeit statt direktem [Dispatchers.IO], damit Tests ihn ersetzen koennen.
     */
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
