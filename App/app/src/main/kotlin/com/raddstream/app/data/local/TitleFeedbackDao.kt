package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TitleFeedbackDao {

    @Query("SELECT * FROM title_feedback")
    suspend fun allOnce(): List<TitleFeedback>

    @Query("SELECT * FROM title_feedback WHERE workRef = :workRef LIMIT 1")
    suspend fun findByWorkRef(workRef: String): TitleFeedback?

    @Query("SELECT thumb FROM title_feedback WHERE workRef = :workRef LIMIT 1")
    fun thumbFor(workRef: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: TitleFeedback)

    @Query("DELETE FROM title_feedback WHERE workRef = :workRef")
    suspend fun deleteByWorkRef(workRef: String)
}
