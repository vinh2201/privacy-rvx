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

// === DANH SÁCH THÊM MỚI ===
private val EXCLUDED_ROOT_CALLS = listOf(
    // === NHÓM GOOGLE PLAY SERVICES ===
    "play-services-auth.properties",
    "play-services-auth-api-phone.properties",
    "play-services-auth-base.properties",
    "play-services-base.properties",
    "play-services-cloud-messaging.properties",
    "play-services-gcm.properties",
    "play-services-tasks.properties",

    // === NHÓM FIREBASE ===
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

    // === NHÓM KHÁC ===
    "core-common.properties",
    "META-INF/androidx.compose.ui_ui.version",
    "androidannotations-api.properties",
    "jetty-dir.css",
    "feature-delivery.properties",
    "feature-delivery-base.properties"
)

private val PACKAGE_NAME = listOf(
    "com.viber.voip", "com.facebook.orca", "com.whatsapp", "com.zing.zalo"
)
// =========================

// Danh sách bắn tỉa trực tiếp cho các file rác nằm ở root
private val EXACT_ROOT_JUNK = listOf(
    // === NHÓM GOOGLE PLAY SERVICES ===
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

    // === NHÓM FIREBASE ===
    "firebase/perf/v1/perf_metric.proto",
    "firebase-ads.properties",
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

    // === NHÓM TRANSPORT ===
    "transport-api.properties",
    "transport-backend-cct.properties",
    "transport-runtime.properties",

    // === BỔ SUNG CÁC MỤC BỊ SÓT SO VỚI REGEX LIST ===
    "ion-java.properties",
    "facebook_trackers.xml",
    "google_trackers.xml",
    "firebase_trackers.xml",

    // === NHÓM RÁC LẺ & PROTO ===
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
    "tv-ads.properties"
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

        // === KIỂM TRA PACKAGE CHÍNH XÁC Ở ĐẦU MANIFEST ===
        var isExcludedApp = false
        var detectedPackage = "unknown"
        
        try {
            val manifestFile = get("AndroidManifest.xml")
            if (manifestFile.isFile) {
                val rawBytes = manifestFile.readBytes()
                val strUtf8 = String(rawBytes, Charsets.UTF_8)
                // Đảm bảo không nổ lỗi encoding, ưu tiên chuỗi có chứa "<manifest"
                val text = if (strUtf8.contains("<manifest")) strUtf8 else String(rawBytes, Charsets.UTF_16LE)
                
                // Chỉ lấy 5 dòng đầu tiên để tóm đúng dòng thứ 2 chứa <manifest, bỏ qua toàn bộ rác bên dưới
                val topLines = text.lines().take(5)
                val manifestLine = topLines.find { it.contains("<manifest") }
                
                if (manifestLine != null) {
                    for (pkg in PACKAGE_NAME) {
                        if (manifestLine.contains("package=\"$pkg\"") || manifestLine.contains("package='$pkg'")) {
                            isExcludedApp = true
                            detectedPackage = pkg
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("APK Cleanup: Failed to verify package from raw Manifest - ${e.message}")
        }

        if (isExcludedApp) {
            logger.info("APK Cleanup: Detected protected package ($detectedPackage). Applying EXCLUDED_ROOT_CALLS rules.")
        }

        //==================================================

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        // Đưa trực tiếp điều kiện kiểm tra EXCLUDED_ROOT_CALLS vào removeTree
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

        // Tự động được bảo vệ bởi EXCLUDED_ROOT_CALLS thông qua hàm removeTree đã tích hợp kiểm tra
        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        JUNK_DIRECTORY_PREFIXES.forEach { prefix ->
            try { removeTree(prefix.removeSuffix("/")) } catch (_: Exception) {}
        }

        // Vòng lặp META-INF gọi removeTree sẽ tự động kiểm tra path đầy đủ (ví dụ: META-INF/androidx.compose.ui_ui.version)
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