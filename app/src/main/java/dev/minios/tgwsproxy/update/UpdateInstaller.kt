package dev.minios.tgwsproxy.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.minios.tgwsproxy.BuildConfig
import java.io.File
import java.security.MessageDigest

object UpdateInstaller {

    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun verify(context: Context, apk: File, release: UpdateInfo) {
        require(apk.isFile && apk.length() > 0L) { "Downloaded APK is empty" }
        val packageManager = context.packageManager
        val archive = packageInfo(packageManager, apk.absolutePath)
            ?: error("Downloaded file is not a valid APK")
        val installed = packageInfo(packageManager, context.packageName)
            ?: error("Installed package information is unavailable")

        require(archive.packageName == context.packageName) { "APK package name mismatch" }
        require(archive.versionName == release.versionName) { "APK version name mismatch" }
        val archiveVersionCode = versionCode(archive)
        require(archiveVersionCode > BuildConfig.VERSION_CODE) { "APK version is not newer" }
        release.versionCode?.let {
            require(archiveVersionCode == it.toLong()) { "APK version code mismatch" }
        }
        require(signingCertificates(archive) == signingCertificates(installed)) {
            "APK signing certificate mismatch"
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, source: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (source.endsWith(".apk")) {
            packageManager.getPackageArchiveInfo(source, flags)
        } else {
            packageManager.getPackageInfo(source, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        require(signatures.isNotEmpty()) { "APK signing certificate is unavailable" }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
