package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppBlockDatabase
import com.example.data.AppBlockRepository
import com.example.data.AppBlockRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val blockedMobileData: Boolean,
    val blockedWifi: Boolean
)

enum class FilterType {
    ALL,
    BLOCKED,
    USER_ONLY,
    SYSTEM_ONLY,
    MOBILE_BLOCKED,
    WIFI_BLOCKED
}

class FirewallViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppBlockRepository
    private val packageManager: PackageManager = application.packageManager
    private val ownPackageName: String = application.packageName

    // Raw cache of installed system & user apps
    private val installedAppsCache = MutableStateFlow<List<RawAppInfo>>(emptyList())

    // UI filters
    val searchQuery = MutableStateFlow("")
    val selectedFilter = MutableStateFlow(FilterType.ALL)

    // Reactive status flows mirroring VpnService companion values
    val isFirewallActive = MyVpnService.isRunning.asStateFlow()
    val activeBlockedCount = MyVpnService.blockedAppCount.asStateFlow()

    // Loading status
    val isLoading = MutableStateFlow(true)

    // Computed list of apps combinator
    val appList: StateFlow<List<AppItem>>

    private data class RawAppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val isSystem: Boolean
    )

    init {
        val database = AppBlockDatabase.getInstance(application)
        repository = AppBlockRepository(database.appBlockRuleDao)

        // Combine installed application lists and database rules reactively
        appList = combine(
            installedAppsCache,
            repository.allRulesFlow,
            searchQuery,
            selectedFilter
        ) { rawApps, rules, query, filter ->
            val ruleMap = rules.associateBy { it.packageName }
            
            rawApps.map { raw ->
                val rule = ruleMap[raw.packageName]
                AppItem(
                    packageName = raw.packageName,
                    appName = raw.appName,
                    icon = raw.icon,
                    isSystem = raw.isSystem,
                    blockedMobileData = rule?.blockedMobileData ?: false,
                    blockedWifi = rule?.blockedWifi ?: false
                )
            }.filter { item ->
                // Apply Search filter
                val matchesSearch = item.appName.contains(query, ignoreCase = true) || 
                                    item.packageName.contains(query, ignoreCase = true)
                if (!matchesSearch) return@filter false

                // Apply Tab/Type Category filter
                when (filter) {
                    FilterType.ALL -> true
                    FilterType.BLOCKED -> item.blockedMobileData || item.blockedWifi
                    FilterType.USER_ONLY -> !item.isSystem
                    FilterType.SYSTEM_ONLY -> item.isSystem
                    FilterType.MOBILE_BLOCKED -> item.blockedMobileData
                    FilterType.WIFI_BLOCKED -> item.blockedWifi
                }
            }.sortedBy { it.appName.lowercase() }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Load the applications in a background thread to prevent UI blocks
        loadInstalledApplications()
    }

    private fun loadInstalledApplications() {
        viewModelScope.launch(Dispatchers.Default) {
            isLoading.value = true
            try {
                val rawApps = mutableListOf<RawAppInfo>()
                val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

                for (appInfo in packages) {
                    // Exclude our own application to avoid loops
                    if (appInfo.packageName == ownPackageName) continue

                    val label = appInfo.loadLabel(packageManager).toString()
                    val icon = try {
                        appInfo.loadIcon(packageManager)
                    } catch (_: Exception) {
                        null
                    }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    rawApps.add(
                        RawAppInfo(
                            packageName = appInfo.packageName,
                            appName = label,
                            icon = icon,
                            isSystem = isSystem
                        )
                    )
                }

                installedAppsCache.value = rawApps
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading.value = false
            }
        }
    }

    fun toggleMobileDataBlock(packageName: String, currentBlockedState: Boolean, label: String, isSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentRule = repository.getRuleForPackage(packageName)
            val updatedRule = if (currentRule == null) {
                AppBlockRule(
                    packageName = packageName,
                    appName = label,
                    isSystemApp = isSystem,
                    blockedMobileData = !currentBlockedState,
                    blockedWifi = false
                )
            } else {
                currentRule.copy(blockedMobileData = !currentBlockedState)
            }
            repository.saveRule(updatedRule)
            triggerVpnRebuild()
        }
    }

    fun toggleWifiBlock(packageName: String, currentBlockedState: Boolean, label: String, isSystem: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentRule = repository.getRuleForPackage(packageName)
            val updatedRule = if (currentRule == null) {
                AppBlockRule(
                    packageName = packageName,
                    appName = label,
                    isSystemApp = isSystem,
                    blockedMobileData = false,
                    blockedWifi = !currentBlockedState
                )
            } else {
                currentRule.copy(blockedWifi = !currentBlockedState)
            }
            repository.saveRule(updatedRule)
            triggerVpnRebuild()
        }
    }

    fun blockAllVisibleMobileData(block: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = appList.value
            for (app in currentList) {
                val existing = repository.getRuleForPackage(app.packageName)
                val rule = if (existing == null) {
                    AppBlockRule(
                        packageName = app.packageName,
                        appName = app.appName,
                        isSystemApp = app.isSystem,
                        blockedMobileData = block,
                        blockedWifi = false
                    )
                } else {
                    existing.copy(blockedMobileData = block)
                }
                repository.saveRule(rule)
            }
            triggerVpnRebuild()
        }
    }

    fun blockAllVisibleWifi(block: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = appList.value
            for (app in currentList) {
                val existing = repository.getRuleForPackage(app.packageName)
                val rule = if (existing == null) {
                    AppBlockRule(
                        packageName = app.packageName,
                        appName = app.appName,
                        isSystemApp = app.isSystem,
                        blockedMobileData = false,
                        blockedWifi = block
                    )
                } else {
                    existing.copy(blockedWifi = block)
                }
                repository.saveRule(rule)
            }
            triggerVpnRebuild()
        }
    }

    fun applyGlobalActionToggle(context: Context) {
        val intent = Intent(context, MyVpnService::class.java)
        if (isFirewallActive.value) {
            intent.action = MyVpnService.ACTION_STOP
        } else {
            intent.action = MyVpnService.ACTION_START
        }
        context.startService(intent)
    }

    private fun triggerVpnRebuild() {
        if (isFirewallActive.value) {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_REBUILD
            }
            context.startService(intent)
        }
    }
}
