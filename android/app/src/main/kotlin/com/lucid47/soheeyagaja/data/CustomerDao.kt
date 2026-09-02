package com.lucid47.soheeyagaja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customers: List<CustomerEntity>): List<Long>

    @Insert
    suspend fun insertOne(customer: CustomerEntity): Long

    @Update
    suspend fun update(customer: CustomerEntity)

    @Insert
    suspend fun insertCustomFields(fields: List<CustomerCustomFieldEntity>)

    @Query("DELETE FROM customer_custom_fields WHERE customerId = :customerId")
    suspend fun deleteCustomFields(customerId: Long)

    @Transaction
    @Query("SELECT * FROM customers WHERE listId = :listId ORDER BY name COLLATE NOCASE, sourceRow")
    fun observeByList(listId: Long): Flow<List<CustomerWithFields>>

    @Transaction
    @Query("SELECT * FROM customers WHERE id = :customerId")
    fun observeById(customerId: Long): Flow<CustomerWithFields?>

    @Query("SELECT * FROM customers WHERE id = :customerId")
    suspend fun getById(customerId: Long): CustomerEntity?

    @Query("SELECT COALESCE(MAX(sourceRow), 0) FROM customers WHERE listId = :listId")
    suspend fun maxSourceRow(listId: Long): Long

    @Query(
        "SELECT EXISTS(SELECT 1 FROM customers WHERE listId = :listId " +
            "AND normalizedPhone = :normalizedPhone AND id != :excludingId)",
    )
    suspend fun phoneExists(listId: Long, normalizedPhone: String, excludingId: Long): Boolean

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteById(customerId: Long)

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

    @Query("SELECT * FROM customer_lists WHERE id = :listId")
    suspend fun getById(listId: Long): CustomerListEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM customer_lists WHERE id = :listId)")
    suspend fun exists(listId: Long): Boolean

    @Query("UPDATE customer_lists SET updatedAtEpochMillis = :updatedAt WHERE id = :listId")
    suspend fun touch(listId: Long, updatedAt: Long)

    @Query("UPDATE customer_lists SET name = :name, updatedAtEpochMillis = :updatedAt WHERE id = :listId")
    suspend fun rename(listId: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM customer_lists WHERE id = :listId")
    suspend fun deleteById(listId: Long)
}
