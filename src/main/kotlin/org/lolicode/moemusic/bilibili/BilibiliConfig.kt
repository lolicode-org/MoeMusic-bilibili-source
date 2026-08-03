package org.lolicode.moemusic.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BilibiliConfig(
    /** Public favorite collection media_id/fid. Zero disables Bilibili autoplay. */
    @SerialName("autoplay_favorite_collection_id")
    val autoplayFavoriteCollectionId: Long = 0,
)
