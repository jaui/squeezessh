package dev.jaui.squeezessh.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jaui.squeezessh.data.db.AppDatabase
import dev.jaui.squeezessh.util.TimeSource
import org.junit.After
import org.junit.Before

/**
 * Basis fuer DAO- und Repository-Tests: In-Memory-Datenbank mit fester Uhr.
 *
 * Fremdschluessel werden explizit eingeschaltet — in der In-Memory-Variante sind sie sonst
 * aus, und CASCADE-/SET-NULL-Verhalten wuerde im Test nicht greifen, in der App aber schon.
 */
abstract class DatabaseTestCase {

    protected lateinit var db: AppDatabase
    protected val vault = FakeSecretVault()

    /** Feste Uhr, die bei jedem Aufruf um eine Sekunde weiterlaeuft. */
    protected var clockMillis: Long = 1_700_000_000_000
    protected val time = TimeSource { clockMillis += 1_000; clockMillis }

    @Before
    fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryCallback({ _, _ -> }, Runnable::run)
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
