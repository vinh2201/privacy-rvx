package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("ApkCleanupPatch")

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
    Regex(""".*(?:^|/)baseline\.profm?$"""),
)

private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "services/",
    "okhttp3/",
)

private val EXCLUDED_PREFIXES = listOf("res/")

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Surgically removes junk directly from Patcher's virtual memory.",
    use = false,
) {
    val splitByArch by booleanOption(
        key = "splitByArch",
        default = false,
        title = "Keep Only One Architecture",
        description = "Keep native libraries (.so files) for only one CPU architecture.",
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
        var removedFiles = 0

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        try {
            // Lấy trực tiếp danh sách file từ context của RawResourcePatchContext
            val allVirtualFiles = files.keys.toList()

            allVirtualFiles.forEach { rawPath ->
                val relativePath = rawPath.replace("\\", "/").removePrefix("unknown/").removePrefix("original/")

                if (isProtected(relativePath)) return@forEach
                if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                val shouldDelete = when {
                    JUNK_PATTERNS.any { it.matches(relativePath) } -> true
                    JUNK_DIRECTORY_PREFIXES.any { relativePath.startsWith(it) } -> true
                    relativePath == "kotlin" || relativePath.startsWith("kotlin/") -> true
                    relativePath == "assets/audience_network.dex" ||
                        relativePath.startsWith("assets/audience_network/") -> true
                    relativePath.startsWith("META-INF/") -> true
                    else -> false
                }

                if (shouldDelete) {
                    try {
                        delete(rawPath)
                        removedFiles++
                        logger.fine("Vaporized from memory: $rawPath")
                    } catch (e: Exception) {
                        // Bỏ qua nếu có lỗi xóa
                    }
                }
            }

            // Xử lý tách kiến trúc CPU trực tiếp trên bộ nhớ ảo
            if (splitByArch == true) {
                val archToKeep = targetArch ?: "armeabi-v7a"
                
                val libFiles = allVirtualFiles.filter { it.startsWith("lib/") }
                
                libFiles.forEach { libPath ->
                    val arch = libPath.substringAfter("lib/").substringBefore("/")
                    if (arch != archToKeep) {
                        try {
                            delete(libPath)
                            removedFiles++
                        } catch (_: Exception) {}
                    }
                }
            }

        } catch (e: Exception) {
            logger.warning("Lỗi truy xuất bộ nhớ ảo của Patcher: ${e.message}")
        }

        logger.info("APK Cleanup: successfully vaporized $removedFiles junk entries directly from memory.")
    }
}