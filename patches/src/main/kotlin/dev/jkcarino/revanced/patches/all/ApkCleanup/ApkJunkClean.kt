package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("ApkCleanupPatch")

// Các file tối thượng ở root tuyệt đối không được đụng vào
private val PROTECTED_ROOT_FILES = setOf(
    "AndroidManifest.xml",
    "resources.arsc"
)

private val PROTECTED_PATTERNS = listOf(
    Regex(""".*META-INF/MANIFEST\.MF$"""),
    Regex(""".*META-INF/services/.*"""),
    Regex(""".*META-INF/.*\.(RSA|SF|DSA|EC)$"""),
    Regex("""^(root/)?classes\d*\.dex$"""),
    Regex(""".*resources\.arsc$"""),
    Regex(""".*AndroidManifest\.xml$"""),
)

private val JUNK_PATTERNS = listOf(
    Regex(""".*\.properties$"""), // Diệt sạch mọi file .properties ở mọi ngóc ngách
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
    Regex(""".*THIRD-PARTY-NOTICES\.txt$"""),
    Regex(""".*licenses\.md$"""),
    Regex(""".*debug\.keystore$"""),
    Regex(""".*_trackers\.xml$"""),
    Regex(""".*jetty-dir\.css$"""),
    // ART baseline profiles
    Regex(""".*(?:^|/)baseline\.profm?$"""),
)

// Các tên thư mục rác (có thể nằm ở root hoặc bất cứ đâu trong workspace)
private val JUNK_DIRECTORY_NAMES = listOf(
    "dexopt",
    "audience_network",
    "clevertap",
    "jacoco",
    "joda",
    "services",
    "kotlin"
)

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files with no runtime purpose inside apk workspace.",
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
        var freedBytes = 0L

        // Gốc workspace chính là thư mục hiện tại mà ReVanced CLI đang bung APK ra
        val apkRoot = File(".")
        logger.info("APK Cleanup workspace root: ${apkRoot.absolutePath}")

        fun isProtected(relativePath: String): Boolean {
            if (PROTECTED_ROOT_FILES.contains(relativePath)) return true
            return PROTECTED_PATTERNS.any { it.matches(relativePath) }
        }

        // 1. Quét toàn bộ workspace từ trên xuống
        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (isProtected(relativePath)) return@forEach
                if (relativePath.startsWith("res/")) return@forEach // Giữ nguyên res/

                val isAtRoot = !relativePath.contains("/")
                
                val shouldDelete = when {
                    // Xử lý các file rác nằm chành ành ngay root
                    isAtRoot && !PROTECTED_ROOT_FILES.contains(relativePath) && (
                        relativePath.endsWith(".properties") ||
                        relativePath.endsWith(".txt") ||
                        relativePath.endsWith(".xml") ||
                        relativePath.endsWith(".json") ||
                        relativePath.endsWith(".bin") ||
                        relativePath.endsWith(".version")
                    ) -> true

                    // Xử lý theo regex rác chung
                    JUNK_PATTERNS.any { it.matches(relativePath) } -> true

                    // Xử lý các thư mục rác (dù nằm ở root hay trong assets/ đều dính đòn)
                    JUNK_DIRECTORY_NAMES.any { junkDir ->
                        relativePath == junkDir || 
                        relativePath.startsWith("$junkDir/") || 
                        relativePath.contains("/$junkDir/")
                    } -> true

                    relativePath.startsWith("META-INF/") -> true
                    else -> false
                }

                if (shouldDelete) {
                    val size = file.length()
                    if (file.delete()) {
                        removedFiles++
                        freedBytes += size
                        logger.fine("Cleaned junk file: $relativePath (${size}B)")
                    }
                }
            }

        // 2. Tiêu diệt trọn gói các cây thư mục rác cứng đầu (cả root lẫn assets)
        val targetTrees = listOf(
            "kotlin",
            "assets/audience_network",
            "assets/dexopt",
            "com/clevertap",
            "org/jacoco",
            "org/joda",
            "services",
            "clevertap",
            "jacoco",
            "joda"
        )
        targetTrees.forEach { treePath ->
            try {
                val entry = File(apkRoot, treePath)
                if (entry.exists()) {
                    val size = entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    if (entry.deleteRecursively()) {
                        freedBytes += size
                        logger.info("Removed junk directory tree: $treePath")
                    }
                }
            } catch (e: Exception) {
                logger.warning("Failed to remove tree $treePath: ${e.message}")
            }
        }

        // 3. Xử lý tách kiến trúc CPU (Thay thế --rip-lib)
        if (splitByArch == true) {
            val archToKeep = targetArch ?: "armeabi-v7a"
            val libDir = File(apkRoot, "lib")
            if (libDir.isDirectory) {
                libDir.listFiles()?.forEach { archDir ->
                    if (archDir.isDirectory && archDir.name != archToKeep) {
                        try {
                            val size = archDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            if (archDir.deleteRecursively()) {
                                freedBytes += size
                                logger.info("Stripped unused architecture lib: ${archDir.name}")
                            }
                        } catch (e: Exception) {
                            logger.warning("Failed to strip architecture ${archDir.name}: ${e.message}")
                        }
                    }
                }
            }
        }

        // 4. Dọn sạch các thư mục rỗng sau khi bay màu file
        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        logger.info("APK Cleanup: successfully removed $removedFiles junk files/directories, freed ${freedBytes / 1024}KB.")
    }
}