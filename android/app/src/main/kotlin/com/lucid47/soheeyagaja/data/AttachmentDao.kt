package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insertPhoto(photo: PhotoMemoEntity): Long

    @Insert
    suspend fun insertAudio(audio: AudioMemoEntity): Long

    @Query("SELECT * FROM photo_memos WHERE customerId = :customerId ORDER BY createdAtEpochMillis DESC")
    fun observePhotos(customerId: Long): Flow<List<PhotoMemoEntity>>

    @Query("SELECT * FROM audio_memos WHERE customerId = :customerId ORDER BY createdAtEpochMillis DESC")
    fun observeAudio(customerId: Long): Flow<List<AudioMemoEntity>>

    @Query("SELECT * FROM photo_memos")
    suspend fun getAllPhotos(): List<PhotoMemoEntity>

    @Query("SELECT * FROM audio_memos")
    suspend fun getAllAudio(): List<AudioMemoEntity>

    @Query("DELETE FROM photo_memos WHERE id = :id")
    suspend fun deletePhoto(id: Long)

    @Query("DELETE FROM audio_memos WHERE id = :id")
    suspend fun deleteAudio(id: Long)

    @Query("UPDATE audio_memos SET transcript = :transcript WHERE id = :id")
    suspend fun updateAudioTranscript(id: Long, transcript: String)

    @Query("SELECT COUNT(*) FROM photo_memos WHERE customerId = :customerId")
    suspend fun countPhotos(customerId: Long): Long

    @Query("SELECT COUNT(*) FROM audio_memos WHERE customerId = :customerId")
    suspend fun countAudio(customerId: Long): Long
}
