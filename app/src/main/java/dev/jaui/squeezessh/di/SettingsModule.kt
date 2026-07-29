package dev.jaui.squeezessh.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * Der DataStore wird hier gebaut statt ueber `Context.preferencesDataStore`.
     *
     * Das Delegat legt eine verborgene, prozessweite Instanz an — bequem, aber in Tests
     * nicht zuruecksetzbar. Hier ist er eine gewoehnliche Abhaengigkeit.
     */
    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            File(context.filesDir, "datastore/settings.preferences_pb")
        }
}
