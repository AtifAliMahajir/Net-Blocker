package com.example.data

import kotlinx.coroutines.flow.Flow

class AppBlockRepository(private val dao: AppBlockRuleDao) {
    val allRulesFlow: Flow<List<AppBlockRule>> = dao.getAllRulesFlow()

    suspend fun getAllRules(): List<AppBlockRule> {
        return dao.getAllRules()
    }

    suspend fun getRuleForPackage(packageName: String): AppBlockRule? {
        return dao.getRuleForPackage(packageName)
    }

    suspend fun saveRule(rule: AppBlockRule) {
        val existing = dao.getRuleForPackage(rule.packageName)
        if (existing == null) {
            if (rule.blockedMobileData || rule.blockedWifi) {
                dao.insertRule(rule)
            }
        } else {
            if (!rule.blockedMobileData && !rule.blockedWifi) {
                dao.deleteRule(existing)
            } else {
                dao.updateRule(rule)
            }
        }
    }

    suspend fun deleteRuleForPackage(packageName: String) {
        dao.deleteRuleForPackage(packageName)
    }
}
