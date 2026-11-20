package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TitleSyncPrefsDao {

    @Query("SELECT * FROM title_sync_prefs WHERE workRef = :workRef LIMIT 1")
    suspend fun findByWorkRef(workRef: String): TitleSyncPrefs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prefs: TitleSyncPrefs)

    @Query("DELETE FROM title_sync_prefs WHERE workRef = :workRef")
    suspend fun deleteByWorkRef(workRef: String)
}
