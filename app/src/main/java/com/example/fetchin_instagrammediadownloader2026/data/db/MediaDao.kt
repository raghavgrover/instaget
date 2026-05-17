package com.example.fetchin_instagrammediadownloader2026.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media_items ORDER BY downloadedAt DESC")
    fun getAllItems(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE mediaType = :type ORDER BY downloadedAt DESC")
    fun getAllByType(type: String): Flow<List<MediaItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItem): Long

    @Delete
    suspend fun delete(item: MediaItem)

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaItem?

    @Query("SELECT * FROM media_items WHERE shortcode = :shortcode LIMIT 1")
    suspend fun getByShortcode(shortcode: String): MediaItem?

    @Query("SELECT * FROM media_items WHERE shortcode = :shortcode")
    suspend fun getAllByShortcode(shortcode: String): List<MediaItem>
}
