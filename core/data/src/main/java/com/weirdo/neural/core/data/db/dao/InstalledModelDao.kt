package com.weirdo.neural.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.weirdo.neural.core.data.db.entity.InstalledModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledModelDao {

    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models WHERE modelId = :id")
    suspend fun findById(id: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE modelId = :id")
    suspend fun deleteById(id: String)
}
