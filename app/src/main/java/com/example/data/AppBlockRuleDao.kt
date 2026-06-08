package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBlockRuleDao {
    @Query("SELECT * FROM app_block_rules")
    fun getAllRulesFlow(): Flow<List<AppBlockRule>>

    @Query("SELECT * FROM app_block_rules")
    suspend fun getAllRules(): List<AppBlockRule>

    @Query("SELECT * FROM app_block_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getRuleForPackage(packageName: String): AppBlockRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AppBlockRule)

    @Update
    suspend fun updateRule(rule: AppBlockRule)

    @Delete
    suspend fun deleteRule(rule: AppBlockRule)

    @Query("DELETE FROM app_block_rules WHERE packageName = :packageName")
    suspend fun deleteRuleForPackage(packageName: String)
}
