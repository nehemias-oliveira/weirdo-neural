package com.weirdo.neural.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val modelId: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val contextSize: Int,
    val sha256: String? = null,
    val source: String, // "catalog" | "imported"
    val installedAt: Long = System.currentTimeMillis(),
)
