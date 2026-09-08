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
    Regex(""".*(?:^|/)baseline\.profm?$""")
)

private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "kotlin/",
    "assets/audience_network/",
    "META-INF/"
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
        val manifestFile = get("AndroidManifest.xml")
        val apkRoot = manifestFile.parentFile ?: File(".")

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        apkRoot.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

            if (isProtected(relativePath)) return@forEach
            
            // Bỏ qua assets/ và res/ trừ khi thuộc danh sách thư mục rác chỉ định
            if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) } && 
                !JUNK_DIRECTORY_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

            // Xử lý lọc kiến trúc CPU
            if (splitByArch == true && relativePath.startsWith("lib/")) {
                val archToKeep = targetArch ?: "armeabi-v7a"
                val fileArch = relativePath.split("/").getOrNull(1)
                if (fileArch != null && fileArch != archToKeep) {
                    try {
                        val size = file.length()
                        delete(relativePath)
                        removedFiles++
                        freedBytes += size
                    } catch (_: Exception) {}
                    return@forEach
                }
            }

            // Xử lý xóa rác VFS
            val shouldDelete = JUNK_PATTERNS.any { it.matches(relativePath) } || 
                               JUNK_DIRECTORY_PREFIXES.any { relativePath.startsWith(it) } ||
                               relativePath == "assets/audience_network.dex"

            if (shouldDelete) {
                try {
                    val size = file.length()
                    delete(relativePath)
                    removedFiles++
                    freedBytes += size
                } catch (e: Exception) {
                    logger.warning("APK Cleanup: failed to delete $relativePath from VFS")
                }
            }
        }

        logger.info("APK Cleanup: successfully marked $removedFiles junk files for removal, freeing ~${freedBytes / 1024}KB.")
    }
}