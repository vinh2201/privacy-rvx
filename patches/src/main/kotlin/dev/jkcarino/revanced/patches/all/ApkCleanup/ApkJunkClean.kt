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
    Regex(""".*\.kotlin_module$"""),
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
        val logger = Logger.getLogger(this::class.java.name)
        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }
        fun isJunk(relativePath: String) = JUNK_PATTERNS.any { it.matches(relativePath) }

        // Định vị chính xác thư mục workspace vật lý của Apktool trong java.io.tmpdir
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val apkRoot = tempDir.walkTopDown()
            .maxDepth(4)
            .find { it.isDirectory && File(it, "AndroidManifest.xml").exists() }
            ?: File(".").walkTopDown().find { it.isDirectory && File(it, "AndroidManifest.xml").exists() }

        if (apkRoot != null) {
            logger.info("APK Cleanup: Targeted workspace -> ${apkRoot.absolutePath}")

            // Càn quét toàn bộ file rác từ gốc đến ngọn bằng JUNK_PATTERNS
            apkRoot.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                    if (isProtected(relativePath)) return@forEach
                    if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                    if (isJunk(relativePath)) {
                        val size = file.length()
                        try {
                            if (file.delete()) {
                                removedFiles++
                                freedBytes += size
                                logger.info("Removed Junk Match: $relativePath (${size}B)")
                            } else {
                                logger.warning("APK Cleanup: failed to delete file: $relativePath")
                            }
                        } catch (e: Exception) {
                            logger.warning("APK Cleanup: exception deleting $relativePath: ${e.message}")
                        }
                    }
                }
        } else {
            logger.warning("APK Cleanup: Could not locate active unpacked APK workspace directory.")
        }

        // Dọn dẹp qua Patcher API cho các cụm thư mục bổ trợ và kiến trúc
        fun removeTree(path: String) {
            try {
                val entry = get(path)
                if (entry.isDirectory) {
                    val children = entry.list()
                    children?.forEach { child -> removeTree("$path/$child") }
                    try { delete(path) } catch (_: Exception) {}
                } else if (entry.isFile) {
                    if (isProtected(path)) return
                    val size = entry.length()
                    try {
                        delete(path)
                        removedFiles++
                        freedBytes += size
                        logger.info("Removed VFS tree node: $path (${size}B)")
                    } catch (e: Exception) {
                        logger.warning("APK Cleanup: failed to delete VFS node $path: ${e.message}")
                    }
                }
            } catch (_: Exception) {}
        }

        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            try {
                val libDir = get("lib")
                if (libDir.isDirectory) {
                    val archNames = libDir.list()?.toList() ?: emptyList()
                    val hasTarget = archNames.contains(archToKeep)

                    if (hasTarget) {
                        archNames.filter { it != archToKeep }.forEach { arch ->
                            try { removeTree("lib/$arch") } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}