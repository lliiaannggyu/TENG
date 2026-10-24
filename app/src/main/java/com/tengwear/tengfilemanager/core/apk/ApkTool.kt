package com.tengwear.tengfilemanager.core.apk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tengwear.tengfilemanager.core.fs.StorageGateway
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 安装包分析 / 安装。
 *
 * 安全与合规边界（写死在这里，不允许 UI 层绕过）：
 *  1. 只做「用户主动点击 → 系统安装器确认」这一条路径。
 *     静默安装需要系统签名或 DeviceOwner，普通应用无法做到，本应用也不尝试任何绕过。
 *  2. 安装前必须校验：包名 / 签名摘要 / minSdk / 与已安装版本的关系（升级/降级/签名不一致）。
 *     签名不一致时直接给出"系统会拒绝覆盖安装"的可读结论，而不是让用户反复点。
 *  3. 不提供"提取其他应用 APK"的自动化入口：
 *     提取第三方应用安装包涉及分发风险，本应用只在用户自己选择本机文件时展示信息。
 */
class ApkTool(
    private val context: Context,
    private val fs: StorageGateway,
) {

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val STAGE = "apk-stage"
    }

    data class ApkInfo(
        val uri: Uri,
        val displayName: String,
        val packageName: String?,
        val label: String?,
        val versionName: String?,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val sizeBytes: Long,
        val signerDigests: List<String>,
        val signatureSchemes: String,
        val permissions: List<String>,
        val isBundle: Boolean,
        val splitUris: List<Uri>,
        val splitNames: List<String>,
        val companionObb: List<String>,
        val installedVersionName: String?,
        val installedSignerDigest: String?,
        val blockReason: String?,
        val warnings: List<String>,
    ) {
        val signatureShort: String get() = signerDigests.firstOrNull()?.take(16) ?: "未知"
        val signatureMatchesInstalled: Boolean
            get() = installedSignerDigest == null || signerDigests.firstOrNull() == installedSignerDigest
        val canInstall: Boolean get() = blockReason == null
    }

    // ---------------------------------------------------------------- 分析

    @Suppress("DEPRECATION")
    suspend fun inspect(entry: FileEntry, siblings: List<FileEntry>): ApkInfo = withContext(Dispatchers.IO) {
        val isBundle = entry.extension in setOf("apks", "xapk", "apkm")
        val stagedFiles: List<File> = if (isBundle) unpackBundle(entry) else listOfNotNull(stageFile(entry))
        val base = stagedFiles.firstOrNull { it.name == "base.apk" } ?: stagedFiles.firstOrNull()

        if (base == null) {
            return@withContext ApkInfo(
                uri = entry.uri, displayName = entry.name, packageName = null, label = null,
                versionName = null, versionCode = 0, minSdk = 0, targetSdk = 0, sizeBytes = entry.size,
                signerDigests = emptyList(), signatureSchemes = "—", permissions = emptyList(),
                isBundle = isBundle, splitUris = emptyList(), splitNames = emptyList(),
                companionObb = emptyList(), installedVersionName = null, installedSignerDigest = null,
                blockReason = "无法解析该安装包（可能不是有效 APK）", warnings = emptyList(),
            )
        }

        val pm = context.packageManager
        val archiveInfo: PackageInfo? = pm.getPackageArchiveInfo(
            base.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS,
        )
        if (archiveInfo == null) {
            return@withContext ApkInfo(
                uri = entry.uri, displayName = entry.name, packageName = null, label = null,
                versionName = null, versionCode = 0, minSdk = 0, targetSdk = 0, sizeBytes = entry.size,
                signerDigests = emptyList(), signatureSchemes = "—", permissions = emptyList(),
                isBundle = isBundle, splitUris = emptyList(), splitNames = emptyList(),
                companionObb = emptyList(), installedVersionName = null, installedSignerDigest = null,
                blockReason = "APK 解析失败：包结构损坏或不是 Android 安装包", warnings = emptyList(),
            )
        }

        val appInfo = archiveInfo.applicationInfo
        appInfo?.apply {
            sourceDir = base.absolutePath
            publicSourceDir = base.absolutePath
        }
        val label = runCatching { appInfo?.loadLabel(pm)?.toString() }.getOrNull()

        val signers = runCatching {
            archiveInfo.signingInfo?.apkContentsSigners?.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).toHex()
            } ?: emptyList()
        }.getOrDefault(emptyList())

        val schemes = buildList {
            archiveInfo.signingInfo?.let { si ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (si.hasMultipleSigners()) add("v1/v2 多签名")
                    if (si.hasPastSigningCertificates()) add("含签名谱系")
                }
            }
            if (signers.isNotEmpty()) add("SHA-256")
        }.ifEmpty { listOf("未签名") }.joinToString(" · ")

        val installed = runCatching {
            pm.getPackageInfo(archiveInfo.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        val installedSigner = installed?.signingInfo?.apkContentsSigners?.firstOrNull()?.let { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).toHex()
        }

        val warnings = buildList {
            if (installed != null) {
                val newCode = archiveInfo.longVersionCode
                val oldCode = installed.longVersionCode
                when {
                    newCode < oldCode -> add("降级安装：版本 $oldCode → $newCode，系统可能拒绝")
                    newCode == oldCode -> add("同版本覆盖安装")
                }
                if (installedSigner != null && signers.isNotEmpty() && installedSigner != signers.first()) {
                    add("签名与已安装版本不一致，系统会拒绝覆盖安装（需先卸载）")
                }
            }
            if (entry.extension in setOf("apks", "xapk")) {
                add("分包安装包：将同时安装 ${stagedFiles.size} 个 APK")
            }
        }.toMutableList()

        val obb = siblings.filter { it.name.endsWith(".obb", true) }.map { it.name }
        if (obb.isNotEmpty()) warnings += "同目录有 OBB 数据包，系统安装器不会自动放入 obb 目录"

        val minSdk = archiveInfo.applicationInfo?.minSdkVersion ?: 0
        val blockReason = when {
            !pm.canRequestPackageInstalls() -> "未允许安装未知来源应用，请先在设置中授权"
            minSdk > Build.VERSION.SDK_INT -> "该应用要求 Android API $minSdk，当前设备为 ${Build.VERSION.SDK_INT}"
            signers.isEmpty() -> "安装包未签名，系统会拒绝安装"
            else -> null
        }

        ApkInfo(
            uri = entry.uri,
            displayName = entry.name,
            packageName = archiveInfo.packageName,
            label = label,
            versionName = archiveInfo.versionName,
            versionCode = archiveInfo.longVersionCode,
            minSdk = minSdk,
            targetSdk = archiveInfo.applicationInfo?.targetSdkVersion ?: 0,
            sizeBytes = entry.size,
            signerDigests = signers,
            signatureSchemes = schemes,
            permissions = archiveInfo.requestedPermissions?.take(8)?.toList() ?: emptyList(),
            isBundle = isBundle,
            splitUris = stagedFiles.drop(1).map { Uri.fromFile(it) },
            splitNames = stagedFiles.drop(1).map { it.name },
            companionObb = obb,
            installedVersionName = installed?.versionName,
            installedSignerDigest = installedSigner,
            blockReason = blockReason,
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- 安装

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    @SuppressLint("WearRecents") // 从 Application 上下文启动设置页必须带 NEW_TASK
    fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 调起系统安装器。单 APK 走 ACTION_VIEW（用户可见的确认界面），
     * 分包走 PackageInstaller 会话（多 APK 一次性提交，仍由系统弹确认）。
     */
    @SuppressLint("WearRecents") // 安装器是外部 Activity，必须 NEW_TASK
    fun launchInstaller(info: ApkInfo): String? {
        if (!canInstallPackages()) {
            return "请先允许本应用安装未知来源应用"
        }
        return runCatching {
            if (info.splitUris.isEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(info.uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                null
            } else {
                installMultiApk(info)
                null
            }
        }.getOrElse { t -> t.message?.take(60) ?: "无法启动安装器" }
    }

    private fun installMultiApk(info: ApkInfo) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            info.packageName?.let { setAppPackageName(it) }
        }
        val sessionId = installer.createSession(params)
        // 注意：API 36 起 PackageInstaller.commit(sessionId, ...) 已移除，
        // 必须在 Session 上调用 commit(IntentSender)。
        installer.openSession(sessionId).use { session ->
            val all = listOf(info.uri) + info.splitUris
            all.forEachIndexed { index, uri ->
                val name = "apk-$index.apk"
                val size = fs.sizeOf(uri)
                session.openWrite(name, 0, size).use { out ->
                    fs.openInput(uri).use { input -> input.copyTo(out, 128 * 1024) }
                    session.fsync(out)
                }
            }
            val callback = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, ApkInstallReceiver::class.java)
                    .setAction(ApkInstallReceiver.ACTION_INSTALL_STATUS),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun stageFile(entry: FileEntry): File? = runCatching {
        if (entry.uri.scheme == "file") return File(entry.uri.path!!)
        val dir = File(context.cacheDir, STAGE).apply { mkdirs() }
        val out = File(dir, "stage-${entry.name.hashCode()}.apk")
        fs.openInput(entry.uri).use { input -> out.outputStream().use { input.copyTo(it, 128 * 1024) } }
        out
    }.getOrNull()

    /** .apks / .xapk / .apkm 本质是 ZIP，把里面的 APK 摊到 cache。 */
    private fun unpackBundle(entry: FileEntry): List<File> = runCatching {
        val dir = File(context.cacheDir, "$STAGE/bundle-${System.currentTimeMillis()}").apply { mkdirs() }
        val result = ArrayList<File>()
        net.lingala.zip4j.ZipFile(stageFile(entry) ?: return emptyList()).use { zip ->
            zip.fileHeaders.filter { it.fileName.endsWith(".apk", true) }.forEach { header ->
                val target = File(dir, header.fileName.substringAfterLast('/'))
                zip.getInputStream(header).use { input -> target.outputStream().use { input.copyTo(it, 128 * 1024) } }
                result += target
            }
        }
        // base.apk 优先，其余按名字排序保证稳定
        result.sortedBy { if (it.name == "base.apk") "" else it.name }
    }.getOrDefault(emptyList())

    fun describe(info: ApkInfo): String = buildString {
        append(info.label ?: info.packageName ?: "未知应用")
        info.versionName?.let { append("  v$it") }
        append(" · ${formatBytes(info.sizeBytes)}")
    }
}
