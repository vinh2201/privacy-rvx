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

private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "services/",
)

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

        fun shouldDelete(relativePath: String): Boolean {
            val normalized = relativePath
                .removePrefix("/")
                .removePrefix("unknown/")
                .removePrefix("original/")

            if (isProtected(normalized)) return false
            if (EXCLUDED_PREFIXES.any { normalized.startsWith(it) }) return false

            return when {
                JUNK_PATTERNS.any { it.matches(normalized) } -> true
                JUNK_DIRECTORY_PREFIXES.any { normalized.startsWith(it) } -> true
                normalized == "kotlin" || normalized.startsWith("kotlin/") -> true
                normalized == "assets/audience_network.dex" || normalized.startsWith("assets/audience_network/") -> true
                normalized.startsWith("META-INF/") -> true
                else -> false
            }
        }

        fun deleteFromPatcher(relativePath: String) {
            val normalized = relativePath.removePrefix("/")
            val possibleKeys = listOf(
                normalized,
                "unknown/$normalized",
                "original/$normalized"
            )
            for (key in possibleKeys) {
                try {
                    delete(key)
                } catch (_: Exception) {}
            }
        }

        // 1. Quét và tiêu diệt toàn bộ file rác trên ổ cứng (bao gồm thư mục root, unknown/, original/)
        listOf(apkRoot, File(apkRoot, "unknown"), File(apkRoot, "original")).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown()
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
            }
        }

        // 2. PHẪU THUẬT SẠCH SẼ: Xóa trọn gói tên rác trong "unknownFiles" của "apktool.yml"
        // Đây là bước quyết định để Apktool không thể "hồi sinh" đống bùi nhùi từ APK gốc lúc build lại!
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
                            newLines.add(line)
                        } else {
                            val colonIndex = trimmed.indexOf(':')
                            if (colonIndex != -1) {
                                val filePath = trimmed.substring(0, colonIndex).trim().removeSurrounding("\"", "'")
                                if (shouldDelete(filePath)) {
                                    logger.info("Purged from apktool.yml unknownFiles map: $filePath")
                                    continue // Bỏ qua dòng này, tước quyền bốc hàng của apktool!
                                }
                            }
                            newLines.add(line)
                        }
                    } else {
                        newLines.add(line)
                    }
                }
                ymlFile.writeText(newLines.joinToString("\n"))
            } catch (e: Exception) {
                logger.warning("Failed to sanitize apktool.yml: ${e.message}")
            }
        }

        // 3. Dọn sạch các thư mục rác cứng đầu dạng cây (kotlin, audience_network,...)
        fun removeTree(path: String) {
            val entry = File(apkRoot, path)
            if (!entry.exists()) return
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { child -> removeTree("$path/${child.name}") }
                entry.delete()
            } else if (entry.isFile) {
                val relativePath = entry.relativeTo(apkRoot).path.replace("\\", "/")
                if (shouldDelete(relativePath)) {
                    val size = entry.length()
                    deleteFromPatcher(relativePath)
                    if (entry.delete()) {
                        removedFiles++
                        freedBytes += size
                    }
                }
            }
        }

        listOf("kotlin", "unknown/kotlin", "original/kotlin", "assets/audience_network").forEach {
            try { removeTree(it) } catch (_: Exception) {}
        }

        // 4. Dọn sạch các thư mục rỗng sau khi bay màu file
        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        // 5. Tách kiến trúc CPU (nếu bật tùy chọn)
        if (splitByArch == true) {
            val archToKeep = targetArch ?: "armeabi-v7a"
            listOf("lib", "unknown/lib", "original/lib").forEach { libPath ->
                val libDir = File(apkRoot, libPath)
                if (libDir.isDirectory) {
                    val archNames = libDir.list()?.toList() ?: emptyList()
                    if (archNames.contains(archToKeep)) {
                        archNames.filter { it != archToKeep }.forEach { arch ->
                            try { removeTree("$libPath/$arch") } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        logger.info("APK Cleanup: successfully purged $removedFiles junk files, freed ${freedBytes / 1024}KB.")
    }
}