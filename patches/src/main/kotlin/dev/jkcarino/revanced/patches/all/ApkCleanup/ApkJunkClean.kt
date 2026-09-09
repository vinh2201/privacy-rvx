package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.util.logging.Logger

private val PROTECTED_PATTERNS = listOf(
    Regex(""".*META-INF/MANIFEST\.MF$"""),
    Regex(""".*META-INF/services/.*"""),
    Regex(""".*META-INF/.*\.(RSA|SF|DSA|EC)$"""),
    Regex("""^(root/)?classes\d*\.dex$"""),
    Regex(""".*resources\.arsc$"""),
    Regex(""".*AndroidManifest\.xml$"""),
)

// Dùng chung 1 list JUNK_PATTERNS duy nhất, không khai báo rườm rà nữa
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
        val logger = Logger.getLogger(this::class.java.name)
        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(path: String) = PROTECTED_PATTERNS.any { it.matches(path) }

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                entry.list()?.forEach { child -> removeTree("$path/$child") }
                try { delete(path) } catch (_: Exception) {}
            } else if (entry.isFile) {
                if (isProtected(path)) return
                val size = entry.length()
                try {
                    delete(path)
                    removedFiles++
                    freedBytes += size
                    logger.info("Removed: $path (${size}B)")
                } catch (e: Exception) {
                    logger.warning("Failed to delete $path: ${e.message}")
                }
            }
        }

        // Tận dụng chính các tên file/đuôi từ JUNK_PATTERNS hoặc quét trực tiếp thông qua VFS node
        // Giải pháp sạch: Duyệt qua các file rác tiềm năng ở root bằng cách extract keyword từ JUNK_PATTERNS hoặc check trực tiếp
        // Vì Patcher 21 ẩn root list, ta có thể cho patcher quét qua các entry phổ biến hoặc dùng logic match trực tiếp.
        
        // Hoặc gọn nhất: Trích xuất các tên file cố định từ regex của JUNK_PATTERNS nếu muốn, 
        // nhưng để không phải khai báo 2 lần, ta gom chung vào một hàm check thông minh hơn:
        
        val commonRootJunks = JUNK_PATTERNS.mapNotNull { pattern ->
            // Chuyển đổi thô regex pattern thành tên file cứng nếu match dạng đơn giản, 
            // hoặc giữ lại một list nhỏ gọn sinh tự động từ regex (nếu bác không thích khai báo tay).
            // Tuy nhiên, cách nhanh gọn và an toàn nhất cho VFS là duyệt qua tập hợp các file hay gặp:
            null // Hoặc giữ cơ chế match gọn
        }

        // Nếu Patcher trả về danh sách root qua `get("").list()` (một số bản fix hoặc tùy APK):
        val rootDir = get("")
        if (rootDir.isDirectory) {
            rootDir.list()?.forEach { name ->
                if (!isProtected(name) && JUNK_PATTERNS.any { it.matches(name) }) {
                    removeTree(name)
                }
            }
        }

        // Fallback quét các nhánh thư mục phụ & META-INF / kotlin / lib như bình thường
        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        try {
            get("META-INF").let { metaInf ->
                if (metaInf.isDirectory) {
                    metaInf.list()?.forEach { name ->
                        if (name.lowercase() != "services") {
                            removeTree("META-INF/$name")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            val libDir = get("lib")
            if (libDir.isDirectory) {
                libDir.list()?.filter { it != archToKeep }?.forEach { arch ->
                    removeTree("lib/$arch")
                }
            }
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}