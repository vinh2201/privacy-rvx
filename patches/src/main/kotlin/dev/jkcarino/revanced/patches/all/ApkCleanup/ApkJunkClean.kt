package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

private fun isProtectedFile(relativePath: String): Boolean {
    val name = relativePath.substringAfterLast('/')

    // 1. Các file cấu hình hệ thống cốt lõi bắt buộc phải giữ
    if (name == "AndroidManifest.xml" || name == "resources.arsc") return true

    // 2. Các file DEX chính (classes.dex, classes2.dex, ...) hỗ trợ cả tiền tố root/
    if (name.startsWith("classes") && name.endsWith(".dex")) {
        val middle = name.removePrefix("classes").removeSuffix(".dex")
        if (middle.isEmpty() || middle.all { it.isDigit() }) {
            if (relativePath == name || relativePath == "root/$name") return true
        }
    }

    // 3. Nhóm bảo vệ đặc thù nằm trong thư mục META-INF (Manifest, services, chứng chỉ RSA/SF/DSA/EC)
    if (relativePath.contains("META-INF/")) {
        if (name == "MANIFEST.MF") return true
        if (relativePath.contains("META-INF/services/")) return true
        if (name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".EC")) return true
    }

    return false
}

private fun isJunkFile(relativePath: String): Boolean {
    val name = relativePath.substringAfterLast('/')

    if (name.endsWith(".properties") && (
        name.startsWith("play-services-") ||
        name.startsWith("firebase-") ||
        name.startsWith("feature-delivery") ||
        name.startsWith("transport-") ||
        name.endsWith("app-update.properties") ||
        name.endsWith("billing.properties") ||
        name.endsWith("billing-ktx.properties") ||
        name.endsWith("review.properties") ||
        name.endsWith("hsdp.properties") ||
        name.endsWith("core-common.properties") ||
        name.endsWith("user-messaging-platform.properties") ||
        name.endsWith("ads-mobile-sdk.properties") ||
        name.endsWith("ion-java.properties") ||
        name.endsWith("version.properties") ||
        name.endsWith("integrity.properties") ||
        name.endsWith("androidannotations-api.properties")
    )) return true

    if (name.endsWith(".proto")) return true
    if (name.endsWith(".version")) return true
    if (name.endsWith("_VERSION")) return true
    if (name.endsWith("_trackers.xml")) return true
    if (name.endsWith("DebugProbesKt.bin")) return true
    if (name.endsWith("kotlin-tooling-metadata.json")) return true
    if (name.endsWith("androidsupportmultidexversion.txt")) return true
    if (name.endsWith("stamp-cert-sha256")) return true
    if (name.endsWith("version-control-info.textproto")) return true
    if (name.endsWith("THIRD-PARTY-NOTICES.txt")) return true
    if (name.endsWith("licenses.md")) return true
    if (name.endsWith("jetty-dir.css")) return true
    if (name.endsWith("debug.keystore")) return true
    if (name.endsWith("LICENSES")) return true

    if (relativePath.contains("META-INF/")) {
        if (name.endsWith("CHANGES")) return true
        if (name.endsWith("README.md")) return true
        if (name.startsWith("NOTICE")) return true
        if (name.startsWith("LICENSE")) return true
    }

    return false
}

private val EXCLUDED_PREFIXES = listOf("assets/", "res/")

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files with no runtime purpose inside apk.",
    use = false,
) {
    val splitByArch by booleanOption(
        key = "splitByArch",
        default = false,
        title = "Keep Only One Architecture",
        description = "Keep native libraries (.so files) for only one CPU architecture. To generate separate APKs for each architecture, run this patch multiple times with a different architecture selected each time.",
    )

    val targetArch by stringOption(
        key = "targetArch",
        default = "armeabi-v7a",
        values = mapOf(
            "arm64-v8a" to "ARM64 (arm64-v8a)",
            "armeabi-v7a" to "ARMv7 (armeabi-v7a)",
            "x86" to "x86",
            "x86_64" to "x86_64",
        ),
        title = "Target architecture",
        description = "Which architecture to keep when splitting is enabled.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val manifestFile = get("AndroidManifest.xml")
        val apkRoot = manifestFile.parentFile ?: File(".")

        var removedFiles = 0
        var freedBytes = 0L

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                val children = entry.list()
                children?.forEach { child -> removeTree("$path/$child") }
                try {
                    delete(path)
                } catch (_: Exception) {}
            } else if (entry.isFile) {
                if (isProtectedFile(path)) return
                val size = entry.length()
                try {
                    delete(path)
                    removedFiles++
                    freedBytes += size
                    logger.info("Removed: $path (${size}B)")
                } catch (e: Exception) {
                    logger.warning("APK Cleanup: failed to delete $path: ${e.message}")
                }
            }
        }

        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (isProtectedFile(relativePath)) return@forEach
                if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                if (isJunkFile(relativePath)) {
                    val size = file.length()
                    
                    try {
                        delete(relativePath)
                    } catch (_: Exception) {}

                    try {
                        if (file.delete()) {
                            removedFiles++
                            freedBytes += size
                            logger.info("Removed Junk: $relativePath (${size}B)")
                        }
                    } catch (e: Exception) {
                        logger.warning("APK Cleanup: failed to delete $relativePath: ${e.message}")
                    }
                }
            }

        try {
            removeTree("kotlin")
        } catch (_: Exception) {}

        try {
            removeTree("assets/audience_network.dex")
        } catch (_: Exception) {}

        try {
            removeTree("assets/audience_network")
        } catch (_: Exception) {}

        try {
            val METAINF = get("META-INF")
            if (METAINF.isDirectory) {
                METAINF.list()?.forEach { name ->
                    if (name.lowercase() == "services") return@forEach
                    try {
                        removeTree("META-INF/$name")
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            val libDir = get("lib")

            if (libDir.isDirectory) {
                val archNames = libDir.list()?.toList() ?: emptyList()
                val hasTarget = archNames.contains(archToKeep)

                if (hasTarget) {
                    archNames.filter { it != archToKeep }.forEach { arch ->
                        try {
                            removeTree("lib/$arch")
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        logger.info("APK Cleanup: successfully removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}