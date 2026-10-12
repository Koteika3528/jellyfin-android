package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Where a downloaded item was left when played without the server, and whether it was finished.
 * [pendingSync] - the server does not know yet; sent when it is reachable again.
 */
@Entity(tableName = "download_play_state")
data class PlayStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "position_ticks") val positionTicks: Long,
    @ColumnInfo(name = "played") val played: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean,
)
