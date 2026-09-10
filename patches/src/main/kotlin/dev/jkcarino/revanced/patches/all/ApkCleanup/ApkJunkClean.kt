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

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                val children = entry.list()
                children?.forEach { child -> removeTree("$path/$child") }
                try {
                    delete(path)
                } catch (_: Exception) {}
            } else if (entry.isFile) {
                if (isProtected(path)) return
                val size = entry.length()
                try {
                    delete(path)
                    removedFiles++
                    freedBytes += size
                    logger.info("Removed: $path (${size}B)")
                } catch (e: Exception) {
                    logger.warning("APK Cleanup: failed to delete $path: ${e.message}")
                }
            }
        }

        // 1. Quét Root thông qua nhiều định dạng Path khác nhau để match với JUNK_PATTERNS
        val rootPaths = listOf("", "/", ".")
        var rootSuccessfullyScanned = false

        for (rootPath in rootPaths) {
            try {
                val rootDir = get(rootPath)
                if (rootDir.isDirectory) {
                    val children = rootDir.list()
                    if (children != null && children.isNotEmpty()) {
                        rootSuccessfullyScanned = true
                        logger.info("APK Cleanup: Successfully listed root using path '$rootPath' (${children.size} entries)")
                        children.forEach { name ->
                            if (isProtected(name)) return@forEach
                            if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) return@forEach

                            val coreEntries = listOf("META-INF", "lib", "assets", "kotlin", "res", "AndroidManifest.xml")
                            if (coreEntries.any { name.equals(it, ignoreCase = true) } || name.matches(Regex("classes\\d*\\.dex"))) {
                                return@forEach
                            }

                            if (JUNK_PATTERNS.any { it.matches(name) }) {
                                try { removeTree(name) } catch (_: Exception) {}
                            }
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                // Ignore errors for individual path tests
            }
        }

        if (!rootSuccessfullyScanned) {
            logger.warning("APK Cleanup: Could not dynamically list root directory files.")
        }

        // Các bước xóa cụm quen thuộc
        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        try {
            val metaInf = get("META-INF")
            if (metaInf.isDirectory) {
                metaInf.list()?.forEach { name ->
                    if (name.lowercase() == "services") return@forEach
                    try { removeTree("META-INF/$name") } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
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
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}