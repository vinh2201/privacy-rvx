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

private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "services/",
)

private val EXCLUDED_ROOT_CALLS = listOf(
    "play-services-auth.properties",
    "play-services-auth-api-phone.properties",
    "play-services-auth-base.properties",
    "play-services-base.properties",
    "play-services-cloud-messaging.properties",
    "play-services-gcm.properties",
    "play-services-tasks.properties",
    "firebase-auth.properties",
    "firebase-auth-interop.properties",
    "firebase-common.properties",
    "firebase-components.properties",
    "firebase-core.properties",
    "firebase-database.properties",
    "firebase-datatransport.properties",
    "firebase-inappmessaging.properties",
    "firebase-inappmessaging-display.properties",
    "firebase-messaging.properties",
    "core-common.properties",
    "META-INF/androidx.compose.ui_ui.version",
    "androidannotations-api.properties",
    "jetty-dir.css"
)

private val PACKAGE_NAME = listOf(
    "com.viber.voip", "com.facebook.orca", "com.whatsapp", "com.zing.zalo"
)

private val EXACT_ROOT_JUNK = listOf(
    "play-services-ads.properties",
    "play-services-ads-base.properties",
    "play-services-ads-identifier.properties",
    "play-services-ads-lite.properties",
    "play-services-analytics.properties",
    "play-services-analytics-impl.properties",
    "play-services-appset.properties",
    "play-services-auth.properties",
    "play-services-auth-api-phone.properties",
    "play-services-auth-base.properties",
    "play-services-base.properties",
    "play-services-basement.properties",
    "play-services-cast.properties",
    "play-services-cast-framework.properties",
    "play-services-clearcut.properties",
    "play-services-cloud-messaging.properties",
    "play-services-drive.properties",
    "play-services-fido.properties",
    "play-services-fitness.properties",
    "play-services-games.properties",
    "play-services-gcm.properties",
    "play-services-identity.properties",
    "play-services-location.properties",
    "play-services-maps.properties",
    "play-services-measurement.properties",
    "play-services-measurement-api.properties",
    "play-services-measurement-base.properties",
    "play-services-measurement-impl.properties",
    "play-services-measurement-sdk.properties",
    "play-services-measurement-sdk-api.properties",
    "play-services-oss-licenses.properties",
    "play-services-pay.properties",
    "play-services-places-placereport.properties",
    "play-services-safetynet.properties",
    "play-services-stats.properties",
    "play-services-tasks.properties",
    "play-services-vision.properties",
    "play-services-vision-common.properties",
    "play-services-wallet.properties",
    "play-services-wearable.properties",
    "firebase-analytics.properties",
    "firebase-annotations.properties",
    "firebase-auth.properties",
    "firebase-auth-interop.properties",
    "firebase-common.properties",
    "firebase-components.properties",
    "firebase-config.properties",
    "firebase-core.properties",
    "firebase-crashlytics.properties",
    "firebase-database.properties",
    "firebase-datatransport.properties",
    "firebase-dynamic-links.properties",
    "firebase-encoders.properties",
    "firebase-encoders-proto.properties",
    "firebase-firestore.properties",
    "firebase-iid.properties",
    "firebase-iid-interop.properties",
    "firebase-inappmessaging.properties",
    "firebase-inappmessaging-display.properties",
    "firebase-installations.properties",
    "firebase-installations-interop.properties",
    "firebase-measurement-connector.properties",
    "firebase-messaging.properties",
    "firebase-perf.properties",
    "firebase-storage.properties",
    "transport-api.properties",
    "transport-backend-cct.properties",
    "transport-runtime.properties",
    "client_analytics.proto",
    "messaging_event.proto",
    "messaging_event_extension.proto",
    "app-update.properties", 
    "billing.properties", 
    "billing-ktx.properties", 
    "review.properties", 
    "hsdp.properties", 
    "core-common.properties", 
    "user-messaging-platform.properties", 
    "ads-mobile-sdk.properties", 
    "DebugProbesKt.bin", 
    "androidsupportmultidexversion.txt", 
    "stamp-cert-sha256", 
    "version-control-info.textproto", 
    "kotlin-tooling-metadata.json",
    "LICENSES", 
    "THIRD-PARTY-NOTICES.txt", 
    "licenses.md", 
    "debug.keystore", 
    "version.properties", 
    "integrity.properties", 
    "androidannotations-api.properties", 
    "jetty-dir.css"
)

private val EXCLUDED_PREFIXES = listOf("res/")

private fun getApkPackageName(bytes: ByteArray): String? {
    try {
        if (bytes.size < 8) return null
        fun readInt(b: ByteArray, offset: Int): Int {
            if (offset + 4 > b.size) return 0
            return ((b[offset].toInt() and 0xFF)) or
                   ((b[offset + 1].toInt() and 0xFF) shl 8) or
                   ((b[offset + 2].toInt() and 0xFF) shl 16) or
                   ((b[offset + 3].toInt() and 0xFF) shl 24)
        }

        var offset = 0
        val magic = readInt(bytes, offset)
        if (magic != 0x00080003) return null
        offset += 8

        var stringPoolStrings: List<String> = emptyList()
        var packageStringIndex = -1

        while (offset < bytes.size) {
            val chunkType = readInt(bytes, offset)
            val chunkSize = readInt(bytes, offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > bytes.size) break

            if (chunkType == 0x001C0001) {
                val stringCount = readInt(bytes, offset + 8)
                val stringsStart = offset + readInt(bytes, offset + 20)
                val flags = readInt(bytes, offset + 16)
                val isUtf8 = (flags and 0x100) != 0

                val stringOffsets = IntArray(stringCount)
                for (i in 0 until stringCount) {
                    stringOffsets[i] = readInt(bytes, offset + 28 + i * 4)
                }

                val strings = mutableListOf<String>()
                for (i in 0 until stringCount) {
                    val strOffset = stringsStart + stringOffsets[i]
                    if (strOffset >= bytes.size) {
                        strings.add("")
                        continue
                    }
                    if (isUtf8) {
                        var curr = strOffset
                        val len1 = bytes[curr].toInt() and 0xFF
                        curr += if ((len1 and 0x80) != 0) 2 else 1
                        val sb = StringBuilder()
                        while (curr < bytes.size && bytes[curr] != 0.toByte()) {
                            sb.append(bytes[curr].toInt().toChar())
                            curr++
                        }
                        val str = sb.toString()
                        strings.add(str)
                        if (str == "package") {
                            packageStringIndex = i
                        }
                    } else {
                        var curr = strOffset
                        val charLen = if (curr + 2 <= bytes.size && (readInt(bytes, curr) and 0xFFFF) < 0x8000) {
                            val l = readInt(bytes, curr) and 0xFFFF
                            curr += 2
                            l
                        } else {
                            val l = readInt(bytes, curr)
                            curr += 4
                            l
                        }
                        
                        val sb = StringBuilder()
                        for (c in 0 until charLen) {
                            if (curr + 2 > bytes.size) break
                            val code = (bytes[curr].toInt() and 0xFF) or ((bytes[curr + 1].toInt() and 0xFF) shl 8)
                            if (code == 0) break
                            sb.append(code.toChar())
                            curr += 2
                        }
                        val str = sb.toString()
                        strings.add(str)
                        if (str == "package") {
                            packageStringIndex = i
                        }
                    }
                }
                stringPoolStrings = strings
            }
            offset += chunkSize
        }

        if (packageStringIndex != -1 && stringPoolStrings.isNotEmpty()) {
            var scanOffset = 0
            while (scanOffset + 20 <= bytes.size) {
                val nameIdx = readInt(bytes, scanOffset + 4)
                if (nameIdx == packageStringIndex) {
                    val dataIdx = readInt(bytes, scanOffset + 16)
                    if (dataIdx >= 0 && dataIdx < stringPoolStrings.size) {
                        val candidate = stringPoolStrings[dataIdx]
                        if (candidate.contains(".") && !candidate.contains(" ")) {
                            return candidate
                        }
                    }
                }
                scanOffset += 4
            }
        }
    } catch (e: Exception) {
    }
    return null
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

        var isExcludedApp = false
        var detectedPackage = "unknown"
        
        try {
            val manifestFile = get("AndroidManifest.xml")
            if (manifestFile.isFile) {
                val rawBytes = manifestFile.readBytes()
                val pkgName = getApkPackageName(rawBytes)
                if (pkgName != null) {
                    detectedPackage = pkgName
                    if (PACKAGE_NAME.contains(pkgName)) {
                        isExcludedApp = true
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("APK Cleanup: Failed to verify package from Manifest - ${e.message}")
        }

        if (isExcludedApp) {
            logger.info("APK Cleanup: Detected protected package ($detectedPackage). Applying EXCLUDED_ROOT_CALLS rules.")
        } else {
            logger.info("APK Cleanup: Detected package ($detectedPackage). Running normal cleanup.")
        }

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        fun removeTree(path: String) {
            if (isExcludedApp && EXCLUDED_ROOT_CALLS.contains(path)) return

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

                            if (isExcludedApp && EXCLUDED_ROOT_CALLS.contains(name)) {
                                return@forEach
                            }

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
            } catch (e: Exception) {}
        }

        if (!rootSuccessfullyScanned) {
            logger.warning("APK Cleanup: Could not dynamically list root directory files. Falling back to direct hit targets.")
        }

        EXACT_ROOT_JUNK.forEach { exactName ->
            val entry = get(exactName)
            if (entry.isFile && !isProtected(exactName)) {
                if (isExcludedApp && EXCLUDED_ROOT_CALLS.contains(exactName)) {
                    return@forEach
                }

                val size = entry.length()
                try {
                    delete(exactName)
                    removedFiles++
                    freedBytes += size
                    logger.info("Removed Direct Target: $exactName (${size}B)")
                } catch (e: Exception) {
                    logger.warning("APK Cleanup: Failed to delete direct target $exactName: ${e.message}")
                }
            }
        }

        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        JUNK_DIRECTORY_PREFIXES.forEach { prefix ->
            try { removeTree(prefix.removeSuffix("/")) } catch (_: Exception) {}
        }

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