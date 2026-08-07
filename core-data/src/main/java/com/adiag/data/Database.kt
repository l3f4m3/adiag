package com.adiag.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "dtc_definitions", primaryKeys = ["code", "manufacturer", "locale"])
data class DtcDefinition(
    val code: String,
    val manufacturer: String,
    val description: String,
    val type: String,
    val locale: String,
    @ColumnInfo(name = "is_generic") val isGeneric: Boolean,
    @ColumnInfo(name = "source_file") val sourceFile: String?,
)

@Dao
interface DtcDao {
    /**
     * Devuelve todas las definiciones de un codigo. El orden de la cadena de
     * fabricantes se resuelve en Kotlin porque depende del vehiculo activo.
     */
    @Query("SELECT * FROM dtc_definitions WHERE code = :code AND locale IN (:locales)")
    suspend fun definitionsFor(code: String, locales: List<String>): List<DtcDefinition>

    @Query("SELECT COUNT(*) FROM dtc_definitions")
    suspend fun count(): Int

    @Query("SELECT * FROM dtc_definitions WHERE locale = :locale AND description LIKE '%' || :q || '%' LIMIT :limit")
    suspend fun search(q: String, locale: String = "es", limit: Int = 50): List<DtcDefinition>
}

@Database(entities = [DtcDefinition::class], version = 1, exportSchema = false)
abstract class AdiagDatabase : RoomDatabase() {
    abstract fun dtcDao(): DtcDao

    companion object {
        const val ASSET = "dtc_codes.db"
        const val FILE = "adiag-dtc.db"

        fun build(context: Context): AdiagDatabase =
            Room.databaseBuilder(context, AdiagDatabase::class.java, FILE)
                .createFromAsset(ASSET)
                .fallbackToDestructiveMigration()
                .build()
    }
}
