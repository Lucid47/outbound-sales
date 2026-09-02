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
}

@Dao
interface CustomerListDao {
    @Insert
    suspend fun insert(customerList: CustomerListEntity): Long

    @Query(
        """
        SELECT customer_lists.id, customer_lists.name, customer_lists.sourceName,
               customer_lists.createdAtEpochMillis, COUNT(customers.id) AS customerCount
        FROM customer_lists
        LEFT JOIN customers ON customers.listId = customer_lists.id
        GROUP BY customer_lists.id
        ORDER BY customer_lists.createdAtEpochMillis DESC
        """,
    )
    fun observeSummaries(): Flow<List<CustomerListSummary>>
}
