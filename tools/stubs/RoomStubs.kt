package androidx.room

import kotlin.reflect.KClass

// Compile-time stand-ins for the Room annotations so the pure-JVM logic can be built without the Android SDK.
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val primaryKeys: Array<String> = []
)

annotation class Index(vararg val value: String, val unique: Boolean = false)

annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = 1
) {
    companion object {
        const val CASCADE = 5
        const val SET_NULL = 3
    }
}

annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class Embedded
