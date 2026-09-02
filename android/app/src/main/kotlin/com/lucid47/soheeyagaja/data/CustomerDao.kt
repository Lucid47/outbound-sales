package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customers: List<CustomerEntity>): List<Long>

    @Query("SELECT duplicateKey FROM customers WHERE listId = :listId")
    suspend fun duplicateKeys(listId: Long): List<String>

    @Query("SELECT COUNT(*) FROM customers WHERE listId = :listId")
    suspend fun count(listId: Long): Long

    @Query("SELECT normalizedPhone FROM customers WHERE normalizedPhone != ''")
    suspend fun allNormalizedPhones(): List<String>
}

@Dao
interface CustomerListDao {
    @Insert
    suspend fun insert(customerList: CustomerListEntity): Long

    @Query(
        """
        SELECT customer_lists.id, customer_lists.name, customer_lists.sourceName,
               customer_lists.createdAtEpochMillis, customer_lists.updatedAtEpochMillis,
               COUNT(customers.id) AS customerCount
        FROM customer_lists
        LEFT JOIN customers ON customers.listId = customer_lists.id
        GROUP BY customer_lists.id
        ORDER BY customer_lists.updatedAtEpochMillis DESC
        """,
    )
    fun observeSummaries(): Flow<List<CustomerListSummary>>

    @Query("SELECT EXISTS(SELECT 1 FROM customer_lists WHERE id = :listId)")
    suspend fun exists(listId: Long): Boolean

    @Query("UPDATE customer_lists SET updatedAtEpochMillis = :updatedAt WHERE id = :listId")
    suspend fun touch(listId: Long, updatedAt: Long)
}
