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

private val JUNK_PATTERNS = listOf(
    Regex(""".*play-services-.*\.properties$"""),
    Regex(""".*firebase-.*\.properties$"""),
    Regex(""".*app-update\.properties$"""),
    Regex(""".*billing\.properties$"""),
    Regex(""".*billing-ktx\.properties$"""),
    Regex(""".*review\.properties$"""),
    Regex(""".*hsdp\.properties$"""),
    Regex(""".*core-common\.properties$"""),
    Regex(""".*user-messaging-platform\.properties$"""),
    Regex(""".*feature-delivery.*\.properties$"""),
    Regex(""".*ads-mobile-sdk\.properties$"""),
    Regex(""".*\.proto$"""),
    Regex(""".*DebugProbesKt\.bin$"""),
    Regex(""".*\.version$"""),
    Regex(""".*_VERSION$"""),
    Regex(""".*androidsupportmultidexversion\.txt$"""),
    Regex(""".*stamp-cert-sha256$"""),
    Regex(""".*version-control-info\.textproto$"""),
    Regex(""".*kotlin-tooling-metadata\.json$"""),
    Regex(""".*META-INF/CHANGES$"""),
    Regex(""".*META-INF/README\.md$"""),
    Regex(""".*META-INF/NOTICE.*"""),
    Regex(""".*META-INF/LICENSE.*"""),
    Regex(""".*(?:^|/)LICENSES$"""),
    Regex(""".*ion-java\.properties$"""),
    Regex(""".*THIRD-PARTY-NOTICES\.txt$"""),
    Regex(""".*licenses\.md$"""),
    Regex(""".*debug\.keystore$"""),
    Regex(""".*_trackers\.xml$"""),
)

// Danh sách các tên file mục tiêu cần quét thẳng (Direct Target Check) để kiểm tra vị trí chính xác
private val DIRECT_TARGET_NAMES = setOf(
    "billing.properties",
    "billing-ktx.properties",
    "firebase-analytics.properties",
    "firebase-annotations.properties",
    "firebase-encoders-proto.properties",
    "firebase-encoders.properties",
    "firebase-iid-interop.properties",
    "firebase-iid.properties",
    "firebase-measurement-connector.properties",
    "client_analytics.proto",
    "messaging_event.proto",
    "messaging_event_extension.proto",
    "DebugProbesKt.bin",
    "androidsupportmultidexversion.txt",
    "app-update.properties",
    "review.properties",
    "hsdp.properties",
    "core-common.properties",
    "user-messaging-platform.properties",
    "ads-mobile-sdk.properties"
)

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

        // Danh sách lưu vết để thống kê chi tiết theo yêu cầu
        val regexMatchReports = mutableListOf<String>()
        val directTargetReports = mutableListOf<String>()

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                val children = entry.list()
                val preview = children?.take(5)?.joinToString()
                logger.info("APK Cleanup: $path/ -> ${children?.size ?: -1} entries (e.g. $preview)")
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
            } else {
                logger.info("APK Cleanup: $path -> neither file nor directory")
            }
        }

        // Chuyển sang dùng walkBottomUp kết hợp diagnostic tracking cho ReVanced
        apkRoot.walkBottomUp()
            .forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                    val fileName = file.name

                    if (isProtected(relativePath)) return@forEach
                    if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                    // 1. Kiểm tra quét thẳng tên file (Direct Target Check)
                    if (DIRECT_TARGET_NAMES.contains(fileName) || DIRECT_TARGET_NAMES.any { relativePath.endsWith("/$it") }) {
                        directTargetReports.add("Direct Target Found: '$fileName' tại vị trí -> $relativePath")
                    }

                    // 2. Kiểm tra quét bằng JUNK_PATTERNS (Regex Match)
                    val matchedRegex = JUNK_PATTERNS.firstOrNull { it.matches(relativePath) }
                    if (matchedRegex != null) {
                        regexMatchReports.add("Regex Match Found: [Pattern: ${matchedRegex.pattern}] tại vị trí -> $relativePath")
                        
                        val size = file.length()
                        try {
                            if (file.delete()) {
                                removedFiles++
                                freedBytes += size
                                logger.info("Removed junk via Regex: $relativePath (${size}B)")
                            } else {
                                logger.warning("APK Cleanup: failed to delete file on disk: $relativePath")
                            }
                        } catch (e: Exception) {
                            logger.warning("APK Cleanup: exception deleting file $relativePath: ${e.message}")
                        }
                    }
                } else if (file.isDirectory && file != apkRoot) {
                    // Dọn dẹp thư mục trống khi đi từ dưới lên (Bottom-Up)
                    if (file.listFiles()?.isEmpty() == true) {
                        try {
                            file.delete()
                        } catch (_: Exception) {}
                    }
                }
            }

        // Xuất log tổng kết danh sách vết quét được theo yêu cầu
        logger.info("=== [APK CLEANUP DIAGNOSTIC REPORT] ===")
        logger.info("Tổng số Direct Target quét được: ${directTargetReports.size}")
        directTargetReports.forEach { logger.info("  - $it") }
        logger.info("Tổng số Regex Match quét được: ${regexMatchReports.size}")
        regexMatchReports.forEach { logger.info("  - $it") }
        logger.info("=======================================")

        try {
            removeTree("kotlin")
        } catch (e: Exception) {
            logger.severe("APK Cleanup: failed removing kotlin/ folder: ${e.message}")
        }

        try {
            removeTree("assets/audience_network.dex")
        } catch (e: Exception) {
            logger.severe("APK Cleanup: failed removing assets/audience_network.dex: ${e.message}")
        }

        try {
            removeTree("assets/audience_network")
        } catch (e: Exception) {
            logger.severe("APK Cleanup: failed removing assets/audience_network/: ${e.message}")
        }

        try {
            val metaInf = get("META-INF")
            if (metaInf.isDirectory) {
                metaInf.list()?.forEach { name ->
                    if (name.lowercase() == "services") return@forEach
                    try {
                        removeTree("META-INF/$name")
                    } catch (e: Exception) {
                        logger.severe("APK Cleanup: failed removing META-INF/$name/: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.severe("APK Cleanup: failed scanning META-INF/: ${e.message}")
        }

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
                        } catch (e: Exception) {
                            logger.severe("APK Cleanup: failed removing lib/$arch/: ${e.message}")
                        }
                    }
                } else {
                    logger.warning(
                        "APK Cleanup: selected architecture \"$archToKeep\" not found in lib/. " +
                        "Available: ${archNames.joinToString()}. Keeping all architectures."
                    )
                }
            }
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}