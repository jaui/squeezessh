package dev.jaui.squeezessh.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jaui.squeezessh.data.db.dao.ConnectionDao
import dev.jaui.squeezessh.data.db.dao.ConnectionGroupDao
import dev.jaui.squeezessh.data.db.dao.IdentityDao
import dev.jaui.squeezessh.data.db.dao.KnownHostDao
import dev.jaui.squeezessh.data.db.dao.PluginLogDao
import dev.jaui.squeezessh.data.db.dao.PortForwardDao
import dev.jaui.squeezessh.data.db.dao.SnippetDao
import dev.jaui.squeezessh.data.db.entity.ConnectionEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionGroupMembershipEntity
import dev.jaui.squeezessh.data.db.entity.ConnectionIdentityEntity
import dev.jaui.squeezessh.data.db.entity.IdentityEntity
import dev.jaui.squeezessh.data.db.entity.KnownHostEntity
import dev.jaui.squeezessh.data.db.entity.PluginLogEntity
import dev.jaui.squeezessh.data.db.entity.PortForwardEntity
import dev.jaui.squeezessh.data.db.entity.SnippetEntity

@Database(
    entities = [
        ConnectionEntity::class,
        ConnectionGroupEntity::class,
        ConnectionGroupMembershipEntity::class,
        IdentityEntity::class,
        ConnectionIdentityEntity::class,
        KnownHostEntity::class,
        PortForwardEntity::class,
        SnippetEntity::class,
        PluginLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun connectionDao(): ConnectionDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun identityDao(): IdentityDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun portForwardDao(): PortForwardDao
    abstract fun snippetDao(): SnippetDao
    abstract fun pluginLogDao(): PluginLogDao

    companion object {
        const val NAME = "squeezessh.db"
    }
}
