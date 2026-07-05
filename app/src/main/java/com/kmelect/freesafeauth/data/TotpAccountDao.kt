package com.kmelect.freesafeauth.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TotpAccountDao {
    @Query("SELECT * FROM totp_accounts ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAccounts(): Flow<List<TotpAccountEntity>>

    @Insert
    suspend fun insert(account: TotpAccountEntity)

    @Update
    suspend fun update(account: TotpAccountEntity)

    @Update
    suspend fun updateAll(accounts: List<TotpAccountEntity>)

    @Delete
    suspend fun delete(account: TotpAccountEntity)
}
