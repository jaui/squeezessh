package dev.jaui.squeezessh.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jaui.squeezessh.data.crypto.AndroidKeystoreSecretVault
import dev.jaui.squeezessh.data.crypto.SecretVault
import dev.jaui.squeezessh.data.db.AppDatabase
import dev.jaui.squeezessh.data.db.dao.ConnectionDao
import dev.jaui.squeezessh.data.db.dao.ConnectionGroupDao
import dev.jaui.squeezessh.data.db.dao.IdentityDao
import dev.jaui.squeezessh.data.db.dao.KnownHostDao
import dev.jaui.squeezessh.data.db.dao.PortForwardDao
import dev.jaui.squeezessh.data.db.dao.SnippetDao
import dev.jaui.squeezessh.util.SystemTimeSource
import dev.jaui.squeezessh.util.TimeSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun provideConnectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()

    @Provides fun provideConnectionGroupDao(db: AppDatabase): ConnectionGroupDao =
        db.connectionGroupDao()

    @Provides fun provideIdentityDao(db: AppDatabase): IdentityDao = db.identityDao()

    @Provides fun provideKnownHostDao(db: AppDatabase): KnownHostDao = db.knownHostDao()

    @Provides fun providePortForwardDao(db: AppDatabase): PortForwardDao = db.portForwardDao()

    @Provides fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindSecretVault(impl: AndroidKeystoreSecretVault): SecretVault

    @Binds
    @Singleton
    abstract fun bindTimeSource(impl: SystemTimeSource): TimeSource
}
