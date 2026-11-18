package com.raddstream.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One starred Live TV channel. The channel id is the server's registry id
 * ([com.raddstream.app.network.models.LiveTvChannel.id]) — the only stable
 * name a channel has: the name can change, the stream handle is opaque and
 * rotates, the id is the row. Nothing else is stored: the grid is rebuilt
 * from the fetched channel list, so a starred channel that leaves the
 * registry simply stops appearing, and its row is dead weight, never a
 * stale card.
 *
 * Local-only, like the posts-side favourites before the sync account: no
 * server call names a channel, so there is nothing to sync and no handle
 * to mint. `starredAt` orders the Favourites view newest-starred first.
 */
@Entity(tableName = "live_tv_favourites")
data class LiveTvFavourite(
    @PrimaryKey
    val channelId: Int,
    val starredAt: Long = System.currentTimeMillis(),
)
