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
    // ART baseline profiles
    Regex(""".*(?:^|/)baseline\.profm?$"""),
)

// Directories whose ENTIRE content gets deleted (khớp 100% bản gốc Morphe)
private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "services/",
)

// Chỉ loại trừ res/ để tránh lỗi resources.arsc, assets/ được phép lột sạch rác bên trong.
private val EXCLUDED_PREFIXES = listOf("res/")

private fun getApkRoot(startFile: File): File {
    var current: File? = startFile
    while (current != null) {
        if (File(current, "resources.arsc").exists() && File(current, "AndroidManifest.xml").exists()) {
            return current
        }
        current = current.parentFile
    }
    return startFile.parentFile ?: File(".")
}

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
        val apkRoot = getApkRoot(manifestFile)

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        // Hàm kiểm tra rác tập trung, đồng bộ toàn diện giữa Morphe & ReVanced
        fun shouldDelete(relativePath: String): Boolean {
            if (isProtected(relativePath)) return false
            if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return false

            return when {
                JUNK_PATTERNS.any { it.matches(relativePath) } -> true
                JUNK_DIRECTORY_PREFIXES.any { relativePath.startsWith(it) } -> true
                relativePath == "kotlin" || relativePath.startsWith("kotlin/") -> true
                relativePath == "assets/audience_network.dex" || relativePath.startsWith("assets/audience_network/") -> true
                // Các file rác nằm trong META-INF (không bị bảo vệ)
                relativePath.startsWith("META-INF/") -> true
                else -> false
            }
        }

        // Bắn phá trực tiếp vào VFS của apk-editor / revanced patcher
        fun deleteFromPatcher(relativePath: String) {
            val normalizedPath = relativePath.removePrefix("/")
            val possibleKeys = listOf(
                normalizedPath,
                "unknown/$normalizedPath",
                "original/$normalizedPath"
            )
            for (key in possibleKeys) {
                try {
                    delete(key)
                } catch (_: Exception) {}
            }
        }

        // 1. Quét và triệt tiêu rác trên ổ cứng tạm & VFS
        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (shouldDelete(relativePath)) {
                    val size = file.length()
                    deleteFromPatcher(relativePath)

                    if (file.delete()) {
                        removedFiles++
                        freedBytes += size
                        logger.fine("Cleaned junk file: $relativePath (${size}B)")
                    }
                }
            }

        // 2. PHẪU THUẬT SẠCH SẼ: Lột sạch tên rác khỏi "apktool.yml" (Mục unknownFiles)
        val ymlFile = File(apkRoot, "apktool.yml")
        if (ymlFile.exists()) {
            try {
                val lines = ymlFile.readLines()
                val newLines = mutableListOf<String>()
                var inUnknownFiles = false

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("unknownFiles:")) {
                        inUnknownFiles = true
                        newLines.add(line)
                        continue
                    }
                    if (inUnknownFiles) {
                        if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                            inUnknownFiles = false
                        } else {
                            val colonIndex = trimmed.indexOf(':')
                            if (colonIndex != -1) {
                                val filePath = trimmed.substring(0, colonIndex).trim().removeSurrounding("\"", "'")
                                val normalized = filePath.removePrefix("unknown/").removePrefix("original/")
                                if (shouldDelete(normalized) || shouldDelete("unknown/$normalized")) {
                                    logger.fine("Removed from apktool.yml unknownFiles: $filePath")
                                    continue // Bỏ qua không ghi lại
                                }
                            }
                        }
                    }
                    newLines.add(line)
                }
                ymlFile.writeText(newLines.joinToString("\n"))
            } catch (e: Exception) {
                logger.warning("Failed to sanitize apktool.yml: ${e.message}")
            }
        }

        // 3. Dọn dẹp các thư mục rác cứng đầu (kotlin, audience_network, META-INF lẻ tẻ)
        fun removeTree(path: String) {
            val entry = File(apkRoot, path)
            if (!entry.exists()) return
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { child -> removeTree("$path/${child.name}") }
                entry.delete()
            } else if (entry.isFile) {
                val relativePath = entry.relativeTo(apkRoot).path.replace("\\", "/")
                val normalized = relativePath.removePrefix("unknown/").removePrefix("original/")
                if (shouldDelete(normalized)) {
                    val size = entry.length()
                    deleteFromPatcher(relativePath)
                    if (entry.delete()) {
                        removedFiles++
                        freedBytes += size
                    }
                }
            }
        }

        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("unknown/kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        listOf("META-INF", "unknown/META-INF", "original/META-INF").forEach { metaPath ->
            try {
                val metaInf = File(apkRoot, metaPath)
                if (metaInf.isDirectory) {
                    metaInf.list()?.forEach { name ->
                        val subPath = "$metaPath/$name"
                        val norm = subPath.removePrefix("unknown/").removePrefix("original/")
                        if (shouldDelete(norm)) {
                            removeTree(subPath)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Dọn sạch các thư mục rỗng sau khi xóa file
        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        // 5. Xử lý tách kiến trúc CPU (nếu bật tùy chọn)
        if (splitByArch == true) {
            val archToKeep = targetArch ?: "armeabi-v7a"
            val libDir = File(apkRoot, "lib")

            if (libDir.isDirectory) {
                val archNames = libDir.list()?.toList() ?: emptyList()
                if (archNames.contains(archToKeep)) {
                    archNames.filter { it != archToKeep }.forEach { arch ->
                        try { removeTree("lib/$arch") } catch (_: Exception) {}
                    }
                }
            }
        }

        logger.info("APK Cleanup: successfully removed $removedFiles junk files, freed ${freedBytes / 1024}KB.")
    }
}