package com.jahangir.app.features.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Data
import com.jahangir.app.workers.DownloadUpdateWorker

import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

import com.jahangir.app.data.models.UpdateState
import com.jahangir.app.features.notification.NotificationEngine
import com.jahangir.shared.notification.TermuxNotificationUtils

class UpdateViewModel : ViewModel() {
    
    var isInitialized = false
    
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    suspend fun initOnStartup(context: Context, currentVersionCode: Long) {
        val workInfos = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .getWorkInfosByTag("download_update")
                .get()
        }
    
        val activeWork = workInfos?.firstOrNull {
            it.state == androidx.work.WorkInfo.State.RUNNING ||
            it.state == androidx.work.WorkInfo.State.ENQUEUED
        }
    
        // এখন directly set করো — caller coroutine Main thread এ আছে
        if (activeWork != null) {
            updateState = UpdateState.Downloading()
            return
        }
    
        val cachedApk = withContext(Dispatchers.IO) {
            findCachedApk(context, currentVersionCode)
        }
    
        if (cachedApk != null) {
            if (isApkValid(cachedApk)) {
                updateState = UpdateState.ReadyToInstall(cachedApk)
            } else {
                cachedApk.delete()
                updateState = UpdateState.Idle
            }
        } else {
            updateState = UpdateState.Idle
        }
    }

    // ── Cache-এ valid APK খোঁজো ───────────────────────────────────────────
    private fun findCachedApk(context: Context, currentVersionCode: Long): File? {
        val cacheDir = context.cacheDir
        // AndroStudio-*.apk pattern খোঁজো
        val apkFiles = cacheDir.listFiles { file ->
            file.name.startsWith("AndroStudio-") && file.name.endsWith(".apk")
        } ?: return null

        for (file in apkFiles) {
            // filename থেকে versionCode বের করো: AndroStudio-1003.apk
            val versionCode = file.nameWithoutExtension
                .removePrefix("AndroStudio-")
                .toLongOrNull() ?: continue

            if (versionCode > currentVersionCode) {
                return file  // valid update বা একই version আছে
            } else {
                file.delete()  // পুরনো version → delete
            }
        }
        return null
    }

    fun validateCachedApk(context: Context, currentVersionCode: Long) {
        val current = updateState
        if (current !is UpdateState.ReadyToInstall) return
    
        if (!current.apkFile.exists()) {
            val stillValid = findCachedApk(context, currentVersionCode)
            updateState = if (stillValid != null) UpdateState.ReadyToInstall(stillValid)
                          else UpdateState.Idle
            return
        }
    
        val cachedVersionCode = current.apkFile.nameWithoutExtension
            .removePrefix("AndroStudio-").toLongOrNull()
    
        when {
            // version পুরনো
            cachedVersionCode != null && currentVersionCode > cachedVersionCode -> {
                current.apkFile.delete()
                updateState = UpdateState.Idle
            }
            // version ঠিক আছে কিন্তু corrupt
            !isApkValid(current.apkFile) -> {
                current.apkFile.delete()
                updateState = UpdateState.Idle
            }
            // সব ঠিক আছে → ReadyToInstall ই থাকবে
        }
    }


    fun isApkValid(file: File): Boolean {
        // ── Check 1: File exists & size reasonable (> 1 MB)
        if (!file.exists() || file.length() < 1024 * 1024) return false
    
        // ── Check 2: ZIP/APK signature check (APK = ZIP format)
        return try {
            val zip = java.util.zip.ZipFile(file)
            zip.close()
            true
        } catch (e: Exception) {
            false  // corrupt zip = corrupt APK
        }
    }
    
    
    fun forceRedownload(context: Context, currentVersionCode: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag("download_update")
        context.cacheDir.listFiles { f ->
            f.name.startsWith("AndroStudio-") && f.name.endsWith(".apk")
        }?.forEach { it.delete() }
        updateState = UpdateState.Checking
        checkForUpdate(context, currentVersionCode)  // ← context পাস করো
    }
    
    private fun findCachedApkForVersion(context: Context, targetVersionCode: Long): File? {
        val file = File(context.cacheDir, "AndroStudio-$targetVersionCode.apk")
        if (!file.exists()) return null
        return if (isApkValid(file)) file else {
            file.delete() // corrupt হলে delete
            null
        }
    }
    

    fun checkForUpdate(context: Context, currentVersionCode: Long) {
        if (updateState is UpdateState.Downloading) return
        
        updateState = UpdateState.Checking
        FirebaseFirestore.getInstance()
            .collection("app_config")
            .document("app_update")
            .get()
            .addOnSuccessListener { doc ->
                val serverCode  = doc.getLong("versionCode")   ?: 0L
                val downloadUrl = doc.getString("downloadUrl") ?: ""
                val serverName  = doc.getString("versionName") ?: ""
    
                if (serverCode > currentVersionCode) {
                    val cachedApk = findCachedApkForVersion(context, serverCode)
                    updateState = if (cachedApk != null)
                        UpdateState.ReadyToInstall(cachedApk)
                    else
                        UpdateState.UpdateAvailable(serverCode, serverName, downloadUrl)
                } else {
                    updateState = UpdateState.UpToDate
                }
            }
            .addOnFailureListener {
                if (updateState is UpdateState.Downloading || 
                    updateState is UpdateState.ReadyToInstall) return@addOnFailureListener
                
                val cachedApk = findCachedApk(context, currentVersionCode)
                updateState = if (cachedApk != null && isApkValid(cachedApk))
                    UpdateState.ReadyToInstall(cachedApk)
                else
                    UpdateState.Idle
            }
    }
    

    fun downloadApk(context: Context, url: String, latestVersionCode: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag("download_update")
        updateState = UpdateState.Downloading(0L, 0L)
        val workRequest = OneTimeWorkRequestBuilder<DownloadUpdateWorker>()
            .setInputData(workDataOf("url" to url, "versionCode" to latestVersionCode))
            .addTag("download_update")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_update_job",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
        // observeDownload এখানে নেই — LaunchedEffect থেকে একবারই হয়েছে
    }
    fun observeDownload(context: Context, latestVersionCode: Long) {
        WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData("download_update")
            .observeForever { workInfos ->
                val info = workInfos?.firstOrNull() ?: return@observeForever
                when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING -> {
                        val downloaded = info.progress.getLong("downloaded", 0L)
                        val total = info.progress.getLong("total", 0L)
                        updateState = UpdateState.Downloading(downloaded, total)
                    }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        // পুরনো SUCCEEDED ignore করো — initOnStartup ইতিমধ্যে cache check করেছে
                        if (updateState is UpdateState.Downloading) {
                            val apkPath = info.outputData.getString("apkPath")
                            if (apkPath != null) {
                                updateState = UpdateState.ReadyToInstall(File(apkPath))
                            }
                        }
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        if (updateState is UpdateState.Downloading) {
                            updateState = UpdateState.Error
                        }
                    }
                    else -> {}
                }
            }
    }
}
