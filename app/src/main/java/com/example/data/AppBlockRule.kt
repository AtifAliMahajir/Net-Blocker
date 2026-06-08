package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_block_rules")
data class AppBlockRule(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val blockedMobileData: Boolean = false,
    val blockedWifi: Boolean = false
)
