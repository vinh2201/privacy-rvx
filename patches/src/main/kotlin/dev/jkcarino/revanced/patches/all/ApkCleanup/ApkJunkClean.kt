package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    description = "Removes junk and useless files inside apk and nested archives.",
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

        fun removeTree(path: String) {
            val entry = File(apkRoot, path)
            if (!entry.exists()) return
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { child -> removeTree("$path/${child.name}") }
                entry.delete()
            } else if (entry.isFile) {
                val rawPath = entry.relativeTo(apkRoot).path.replace("\\", "/")
                val relativePath = rawPath.removePrefix("unknown/").removePrefix("original/")
                if (isProtected(relativePath)) return
                
                val size = entry.length()
                deleteFromPatcher(rawPath)
                
                if (entry.delete()) {
                    removedFiles++
                    freedBytes += size
                }
            }
        }

        // 1. Quét và làm sạch các file nén trá hình (.jar, .zip, .aar) ẩn trong assets/ hoặc toàn bộ cây thư mục
        apkRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("jar", "zip", "aar") }
            .forEach { archive ->
                try {
                    val zipFile = ZipFile(archive)
                    val entries = zipFile.entries().toList()
                    val hasJunkInside = entries.any { entry ->
                        val name = entry.name
                        JUNK_DIRECTORY_PREFIXES.any { name.startsWith(it) } || 
                        JUNK_PATTERNS.any { it.matches(name) } ||
                        name.startsWith("kotlin/") || name == "kotlin"
                    }

                    if (hasJunkInside) {
                        val originalSize = archive.length()
                        val tempFile = File(archive.parentFile, "${archive.name}.tmp")
                        ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
                            zipFile.use { zf ->
                                zf.entries().asSequence().forEach { entry ->
                                    val name = entry.name
                                    val isJunk = JUNK_DIRECTORY_PREFIXES.any { name.startsWith(it) } || 
                                                 JUNK_PATTERNS.any { it.matches(name) } ||
                                                 name.startsWith("kotlin/") || name == "kotlin"
                                    if (!isJunk) {
                                        zos.putNextEntry(ZipEntry(name))
                                        zf.getInputStream(entry).use { it.copyTo(zos) }
                                        zos.closeEntry()
                                    } else {
                                        removedFiles++
                                        logger.fine("Purged from nested archive [${archive.name}]: $name")
                                    }
                                }
                            }
                        }
                        archive.delete()
                        tempFile.renameTo(archive)
                        freedBytes += (originalSize - archive.length())
                        logger.info("Successfully cleaned nested container: ${archive.name}")
                    }
                } catch (e: Exception) {
                    logger.warning("Failed to process nested archive ${archive.name}: ${e.message}")
                }
            }

        // 2. Quét file thông thường ngoài ổ cứng tạm
        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val rawPath = file.relativeTo(apkRoot).path.replace("\\", "/")
                val relativePath = rawPath
                    .removePrefix("unknown/")
                    .removePrefix("original/")

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
                    val size = file.length()
                    deleteFromPatcher(rawPath)

                    if (file.delete()) {
                        removedFiles++
                        freedBytes += size
                    }
                }
            }

        // 3. Dọn dẹp apktool.yml
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
                                val cleanPath = filePath.removePrefix("unknown/").removePrefix("original/")
                                val isJunk = JUNK_PATTERNS.any { it.matches(cleanPath) } || 
                                             JUNK_DIRECTORY_PREFIXES.any { cleanPath.startsWith(it) } ||
                                             cleanPath == "kotlin" || cleanPath.startsWith("okhttp3/")
                                if (isJunk) continue
                            }
                        }
                    }
                    newLines.add(line)
                }
                ymlFile.writeText(newLines.joinToString("\n"))
            } catch (_: Exception) {}
        }

        // 4. Xóa các thư mục rác cứng đầu trên mọi nhánh
        listOf("", "unknown/", "original/").forEach { prefix ->
            JUNK_DIRECTORY_PREFIXES.forEach { dir ->
                try { removeTree("$prefix${dir.removeSuffix("/")}") } catch (_: Exception) {}
            }
            try { removeTree("${prefix}kotlin") } catch (_: Exception) {}
        }

        // Dọn sạch thư mục rỗng
        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        // Xử lý tách kiến trúc CPU
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

        logger.info("APK Cleanup: successfully destroyed $removedFiles junk entries (including inner container files), freed ${freedBytes / 1024}KB.")
    }
}