package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.util.logging.Logger

private val logger = Logger.getLogger("ApkCleanupPatch")

// Danh sách các file/thư mục rác định danh cụ thể hoặc theo đuôi (Pattern matching trong VFS)
private val JUNK_EXTENSIONS = listOf(
    ".properties",
    ".proto",
    ".bin", // DebugProbesKt.bin
    ".version",
    ".txt",
    ".json",
    ".md",
    ".css",
    ".profm",
    ".prof",
    ".keystore",
    ".xml"
)

// Các tiền tố thư mục rác cần dọn sạch bách
private val JUNK_DIRECTORIES = listOf(
    "assets/dexopt",
    "com/clevertap",
    "org/jacoco",
    "org/joda",
    "services",
    "kotlin"
)

private val ALL_ARCHITECTURES = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files with no runtime purpose inside apk directly via Patcher VFS.",
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
        var purgedCount = 0

        // Hàm bao bọc gọi lệnh delete an toàn của Patcher
        fun safeDelete(path: String) {
            try {
                delete(path)
                purgedCount++
                logger.fine("Purged from VFS: $path")
            } catch (_: Exception) {
                // Bỏ qua nếu file không tồn tại trong workspace này
            }
        }

        // 1. Tiêu diệt trọn gói các thư mục rác cứng đầu
        JUNK_DIRECTORIES.forEach { dir ->
            safeDelete(dir)
        }

        // 2. Xử lý phần cắt gọt kiến trúc CPU (Thay thế --rip-lib hoàn toàn)
        val archToKeep = if (splitByArch == true) targetArch ?: "armeabi-v7a" else null
        if (archToKeep != null) {
            logger.info("APK Cleanup: Restricting native libraries to architecture -> $archToKeep")
            ALL_ARCHITECTURES.filter { it != archToKeep }.forEach { arch ->
                // Xóa trọn gói thư mục lib của các kiến trúc không dùng tới
                safeDelete("lib/$arch")
                safeDelete("unknown/lib/$arch")
                safeDelete("original/lib/$arch")
            }
        }

        // 3. Quét dọn META-INF rác (giữ lại các file cốt lõi như MANIFEST.MF và chứng chỉ)
        val metaInfJunks = listOf(
            "META-INF/CHANGES",
            "META-INF/README.md",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/LICENSES",
            "META-INF/THIRD-PARTY-NOTICES.txt"
        )
        metaInfJunks.forEach { safeDelete(it) }

        // 4. Các tệp rác thuộc diện tình nghi cao nằm rải rác ở root hoặc assets/unknown
        val knownJunkFiles = listOf(
            "assets/audience_network.dex",
            "debug.keystore",
            "kotlin-tooling-metadata.json"
        )
        knownJunkFiles.forEach { safeDelete(it) }

        logger.info("APK Cleanup: Successfully executed direct VFS purge. Total targeted nodes cleaned: $purgedCount")
    }
}