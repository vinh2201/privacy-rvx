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
    Regex(""".*version\.properties$"""),
    Regex(""".*integrity\.properties$"""),
    Regex(""".*androidannotations-api\.properties$"""),
    Regex(""".*transport-.*\.properties$"""),
    Regex(""".*jetty-dir\.css$"""),
)

private val EXCLUDED_PREFIXES = listOf("res/")

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

        fun isProtected(path: String) = PROTECTED_PATTERNS.any { it.matches(path) }

        logger.info("=== STARTING HYBRID DISK-WALK & PATCHER DELETE CLEANUP ===")

        // Quét toàn bộ workspace từ thư mục gốc trở xuống bằng walkTopDown
        if (apkRoot.exists() && apkRoot.isDirectory) {
            apkRoot.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                    if (isProtected(relativePath)) return@forEach
                    if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                    if (JUNK_PATTERNS.any { it.matches(relativePath) }) {
                        val size = file.length()
                        try {
                            // Gọi delete chuẩn của Patcher để cập nhật state trong workspace
                            delete(relativePath)
                            removedFiles++
                            freedBytes += size
                            logger.info("Removed Junk: $relativePath (${size}B)")
                        } catch (e: Exception) {
                            // Fallback xóa trực tiếp trên đĩa phòng hờ lỗi Patcher API
                            try {
                                if (file.delete()) {
                                    removedFiles++
                                    freedBytes += size
                                    logger.info("Removed Junk (Disk): $relativePath (${size}B)")
                                } else {
                                    logger.warning("APK Cleanup: failed to delete $relativePath")
                                }
                            } catch (ex: Exception) {
                                logger.warning("APK Cleanup: exception deleting $relativePath: ${ex.message}")
                            }
                        }
                    }
                }
        }

        // Dọn dẹp các thư mục/file rác đặc thù
        val specificPathsToRemove = listOf(
            "kotlin",
            "assets/audience_network.dex",
            "assets/audience_network"
        )

        for (path in specificPathsToRemove) {
            try {
                val entry = get(path)
                val size = if (entry.isFile) entry.length() else 0L
                delete(path)
                removedFiles++
                freedBytes += size
                logger.info("Removed Tree/File: $path")
            } catch (_: Exception) {}
        }

        // Dọn dẹp META-INF ngoại trừ services
        try {
            val metaInf = get("META-INF")
            if (metaInf.isDirectory) {
                metaInf.list()?.forEach { name ->
                    if (name.lowercase() == "services") return@forEach
                    val subPath = "META-INF/$name"
                    try {
                        val entry = get(subPath)
                        val size = if (entry.isFile) entry.length() else 0L
                        delete(subPath)
                        removedFiles++
                        freedBytes += size
                        logger.info("Removed META-INF item: $subPath")
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // Tùy chọn giữ lại architecture cho lib
        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            val libDir = try { get("lib") } catch (_: Exception) { null }

            if (libDir?.isDirectory == true) {
                val archNames = libDir.list()?.toList() ?: emptyList()
                val hasTarget = archNames.contains(archToKeep)

                if (hasTarget) {
                    archNames.filter { it != archToKeep }.forEach { arch ->
                        val libPath = "lib/$arch"
                        try {
                            delete(libPath)
                            logger.info("Removed Architecture: $libPath")
                        } catch (_: Exception) {}
                    }
                } else {
                    logger.warning(
                        "APK Cleanup: selected architecture \"$archToKeep\" not found in lib/. " +
                        "Available: ${archNames.joinToString()}. Keeping all architectures."
                    )
                }
            }
        }

        logger.info("APK Cleanup: successfully removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}