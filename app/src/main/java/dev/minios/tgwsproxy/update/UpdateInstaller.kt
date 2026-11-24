package dev.minios.tgwsproxy.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.minios.tgwsproxy.BuildConfig
import java.io.File
import java.security.MessageDigest

object UpdateInstaller {

    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, release: UpdateInfo) {
        require(apk.isFile && apk.length() > 0L) { "Downloaded APK is empty" }
        val packageManager = context.packageManager
        val packageName = context.packageName
        val signingFlags = signingQueryFlags(Build.VERSION.SDK_INT)
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags)
            ?: error("Downloaded APK could not be parsed or its signing certificates could not be verified")

        require(archive.packageName == packageName) { "APK package name mismatch" }
        require(archive.versionName == release.versionName) { "APK version name mismatch" }
        val archiveVersionCode = versionCode(archive)
        require(archiveVersionCode > BuildConfig.VERSION_CODE) { "APK version is not newer" }
        release.versionCode?.let {
            require(archiveVersionCode == it.toLong()) { "APK version code mismatch" }
        }
        val installed = try {
            packageManager.getPackageInfo(packageName, signingFlags)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Installed package information is unavailable", e)
        }
        verifyMatchingApkSigners(archive.signers(), installed.signers())
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
    private fun versionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): ApkSigners {
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            null
        }
        return ApkSigners(current.certificateBytes(), signatures.certificateBytes())
    }

    private fun Array<out Signature?>?.certificateBytes(): List<ByteArray?>? =
        this?.map { it?.toByteArray() }
}

@Suppress("DEPRECATION")
internal fun signingQueryFlags(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.P) {
        // AOSP Android 10 only collects archive certificates when GET_SIGNATURES is set.
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    } else {
        PackageManager.GET_SIGNATURES
    }

internal class ApkSigners(
    private val current: List<ByteArray?>?,
    private val legacy: List<ByteArray?>?,
) {
    fun certificateDigests(source: String): Set<String> {
        // GET_SIGNATURES may report a historical signer; never use it over current signers.
        val certificates = current?.takeIf { it.isNotEmpty() } ?: legacy
        require(!certificates.isNullOrEmpty()) { "$source signing certificate is unavailable" }
        val sha256 = MessageDigest.getInstance("SHA-256")
        return certificates.mapTo(mutableSetOf()) { certificate ->
            require(certificate != null && certificate.isNotEmpty()) {
                "$source signing certificate is invalid"
            }
            sha256.digest(certificate).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun verifyMatchingApkSigners(archive: ApkSigners, installed: ApkSigners) {
    require(archive.certificateDigests("Downloaded APK") == installed.certificateDigests("Installed package")) {
        "APK signing certificate mismatch"
    }
}
