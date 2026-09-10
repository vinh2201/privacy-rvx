package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

private val PROTECTED_PATTERNS = listOf(
    Regex(""".*META-INF/MANIFEST\.MF$"""),
    Regex(""".*META-INF/services/.*"""),
    Regex(""".*META-INF/.*\.(RSA|SF|DSA|EC)$"""),
    Regex("""^(root/)?classes\d*\.dex$"""),
    Regex(""".*resources\.arsc$"""),
    Regex(""".*AndroidManifest\.xml$"""),
)

// Chuyển hóa toàn bộ JUNK_PATTERNS thành hàm kiểm tra tên/đường dẫn tường minh, cực kỳ an toàn và bao quát
private fun isJunkFile(relativePath: String): Boolean {
    val name = relativePath.substringAfterLast('/')

    // Gom toàn bộ nhóm file .properties bằng cách kết hợp startsWith và endsWith trực tiếp trong 1 điều kiện
    if (name.endsWith(".properties") && (
        name.startsWith("play-services-") ||
        name.startsWith("firebase-") ||
        name.startsWith("feature-delivery") ||
        name.startsWith("transport-") ||
        name == "app-update.properties" ||
        name == "billing.properties" ||
        name == "billing-ktx.properties" ||
        name == "review.properties" ||
        name == "hsdp.properties" ||
        name == "core-common.properties" ||
        name == "user-messaging-platform.properties" ||
        name == "ads-mobile-sdk.properties" ||
        name == "ion-java.properties" ||
        name == "version.properties" ||
        name == "integrity.properties" ||
        name == "androidannotations-api.properties"
    )) return true

    // Các định dạng đuôi mở rộng tổng quát (endsWith)
    if (name.endsWith(".proto")) return true
    if (name.endsWith(".version")) return true
    if (name.endsWith("_VERSION")) return true
    if (name.endsWith("_trackers.xml")) return true
    if (name.endsWith("licenses.md")) return true

    // Các file cụ thể chính xác tên (==)
    if (name == "DebugProbesKt.bin") return true
    if (name == "kotlin-tooling-metadata.json") return true
    if (name == "androidsupportmultidexversion.txt") return true
    if (name == "stamp-cert-sha256") return true
    if (name == "version-control-info.textproto") return true
    if (name == "THIRD-PARTY-NOTICES.txt") return true
    if (name == "debug.keystore") return true
    if (name == "jetty-dir.css") return true
    if (name == "LICENSES") return true

    // Nhóm rác đặc thù nằm bên trong thư mục META-INF
    if (relativePath.startsWith("META-INF/")) {
        if (name == "CHANGES" || name == "README.md") return true
        if (name.startsWith("NOTICE") || name.startsWith("LICENSE")) return true
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

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                val children = entry.list()
                children?.forEach { child -> removeTree("$path/$child") }
                try {
                    delete(path)
                } catch (_: Exception) {}
            } else if (entry.isFile) {
                if (isProtected(path)) return
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

        // Quét cấu trúc đĩa và kiểm tra trực tiếp qua hàm isJunkFile siêu tốc
        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (isProtected(relativePath)) return@forEach
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