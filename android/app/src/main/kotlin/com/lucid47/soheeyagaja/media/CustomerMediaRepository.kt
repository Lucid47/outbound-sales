package com.lucid47.soheeyagaja.media

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lucid47.soheeyagaja.data.AppDatabase
import com.lucid47.soheeyagaja.data.AudioMemoEntity
import com.lucid47.soheeyagaja.data.PhotoMemoEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomerMediaRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    fun observePhotos(customerId: Long) = database.attachmentDao().observePhotos(customerId)

    fun observeAudio(customerId: Long) = database.attachmentDao().observeAudio(customerId)

    fun newCameraFile(customerId: Long): File {
        val directory = photoDirectory(customerId)
        return File(directory, "photo-${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
    }

    suspend fun saveCapturedPhoto(customerId: Long, file: File): Long = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0L) { "촬영된 사진을 찾지 못했습니다." }
        savePhotoMetadata(customerId, file, "카메라 촬영")
    }

    suspend fun importPhotos(customerId: Long, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var saved = 0
        uris.forEachIndexed { index, uri ->
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.take(5) ?: "jpg"
            val destination = File(
                photoDirectory(customerId),
                "import-${System.currentTimeMillis()}-$index-${UUID.randomUUID()}.$extension",
            )
            runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "사진 파일을 열지 못했습니다." }
                    destination.outputStream().buffered().use(input::copyTo)
                }
                savePhotoMetadata(customerId, destination, uri.lastPathSegment ?: "가져온 사진")
            }.onSuccess { saved += 1 }
                .onFailure { destination.delete() }
        }
        saved
    }

    fun newAudioFile(customerId: Long): File {
        val directory = File(context.filesDir, "media/audio/$customerId").apply { mkdirs() }
        return File(directory, "audio-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
    }

    suspend fun saveAudioMemo(
        customerId: Long,
        file: File,
        durationMillis: Long,
        transcript: String,
    ): Long = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0L) { "녹음 파일을 찾지 못했습니다." }
        database.withTransaction {
            val customer = requireNotNull(database.customerDao().getById(customerId)) { "고객을 찾지 못했습니다." }
            val now = System.currentTimeMillis()
            val id = database.attachmentDao().insertAudio(
                AudioMemoEntity(
                    listId = customer.listId,
                    customerId = customer.id,
                    filePath = file.absolutePath,
                    durationMillis = durationMillis.coerceAtLeast(0L),
                    transcript = transcript.trim(),
                    createdAtEpochMillis = now,
                ),
            )
            database.customerListDao().touch(customer.listId, now)
            id
        }
    }

    suspend fun deletePhoto(photo: PhotoMemoEntity) = withContext(Dispatchers.IO) {
        database.attachmentDao().deletePhoto(photo.id)
        File(photo.filePath).delete()
    }

    suspend fun deleteAudio(audio: AudioMemoEntity) = withContext(Dispatchers.IO) {
        database.attachmentDao().deleteAudio(audio.id)
        File(audio.filePath).delete()
    }

    suspend fun customerIdsInList(listId: Long): List<Long> = withContext(Dispatchers.IO) {
        database.customerDao().idsByList(listId)
    }

    suspend fun deleteCustomerFiles(customerIds: Collection<Long>) = withContext(Dispatchers.IO) {
        customerIds.forEach { customerId ->
            File(context.filesDir, "media/photos/$customerId").deleteRecursively()
            File(context.filesDir, "media/audio/$customerId").deleteRecursively()
        }
    }

    private suspend fun savePhotoMetadata(customerId: Long, file: File, originalName: String): Long =
        database.withTransaction {
            val customer = requireNotNull(database.customerDao().getById(customerId)) { "고객을 찾지 못했습니다." }
            val now = System.currentTimeMillis()
            val id = database.attachmentDao().insertPhoto(
                PhotoMemoEntity(
                    listId = customer.listId,
                    customerId = customer.id,
                    filePath = file.absolutePath,
                    originalName = originalName,
                    createdAtEpochMillis = now,
                ),
            )
            database.customerListDao().touch(customer.listId, now)
            id
        }

    private fun photoDirectory(customerId: Long): File =
        File(context.filesDir, "media/photos/$customerId").apply { mkdirs() }
}
