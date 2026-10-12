package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.jellyfin.mobile.data.entity.PlayStateEntity
import java.util.UUID

@Dao
interface PlayStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayStateEntity)

    @Query("SELECT * FROM download_play_state WHERE item_id = :itemId")
    suspend fun get(itemId: UUID): PlayStateEntity?

    @Query("SELECT * FROM download_play_state")
    fun observeAll(): Flow<List<PlayStateEntity>>

    @Query("SELECT * FROM download_play_state WHERE pending_sync = 1 ORDER BY updated_at ASC")
    suspend fun getPending(): List<PlayStateEntity>

    /** Marks the state as sent, unless it changed again in the meantime. */
    @Query("UPDATE download_play_state SET pending_sync = 0 WHERE item_id = :itemId AND updated_at = :updatedAt")
    suspend fun markSynced(itemId: UUID, updatedAt: Long)

    @Query("DELETE FROM download_play_state WHERE item_id = :itemId")
    suspend fun delete(itemId: UUID)
}
