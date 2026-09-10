package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.util.logging.Logger

private fun isProtectedFile(path: String, name: String): Boolean {
    // 1. Các file cấu hình hệ thống cốt lõi bắt buộc phải giữ
    if (name == "AndroidManifest.xml" || name == "resources.arsc") return true

    // 2. Các file DEX chính (classes.dex, classes2.dex, ...)
    if (name.startsWith("classes") && name.endsWith(".dex")) {
        val middle = name.removePrefix("classes").removeSuffix(".dex")
        if (middle.isEmpty() || middle.all { it.isDigit() }) {
            if (path == name || path == "root/$name" || path.endsWith("/$name")) return true
        }
    }

    // 3. Nhóm bảo vệ đặc thù nằm trong thư mục META-INF (Manifest, services, chứng chỉ RSA/SF/DSA/EC)
    if (path.contains("META-INF/")) {
        if (name == "MANIFEST.MF") return true
        if (path.contains("META-INF/services/")) return true
        if (name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".EC")) return true
    }

    return false
}

private fun isJunkFile(path: String, name: String): Boolean {
    // Tuyệt đối không quét nhầm thư mục chứa tài nguyên giao diện
    if (path.startsWith("res/") || path.contains("/res/")) return false

    // Hốt trọn gói tất cả các file .properties rác bất kể tiền tố rườm rà phía trước
    if (name.endsWith(".properties")) return true

    // Nhóm các định dạng file rác phổ biến do build toolchain sinh ra
    if (name.endsWith(".proto")) return true
    if (name.endsWith(".version")) return true
    if (name.endsWith("_VERSION")) return true
    if (name.endsWith("_trackers.xml")) return true
    if (name.endsWith("DebugProbesKt.bin")) return true
    if (name.endsWith("kotlin-tooling-metadata.json")) return true
    if (name.endsWith("androidsupportmultidexversion.txt")) return true
    if (name.endsWith("stamp-cert-sha256")) return true
    if (name.endsWith("version-control-info.textproto")) return true
    if (name.endsWith("THIRD-PARTY-NOTICES.txt")) return true
    if (name.endsWith("licenses.md")) return true
    if (name.endsWith("jetty-dir.css")) return true
    if (name.endsWith("debug.keystore")) return true
    if (name.endsWith("LICENSES")) return true
    if (name.endsWith(".kotlin_module")) return true

    // Rác nằm gọn trong META-INF
    if (path.contains("META-INF/")) {
        if (name.endsWith("CHANGES")) return true
        if (name.endsWith("README.md")) return true
        if (name.startsWith("NOTICE")) return true
        if (name.startsWith("LICENSE")) return true
    }

    return false
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
        var removedFiles = 0
        var freedBytes = 0L

        // 1. Dò tìm chính xác định dạng root path mà ReVanced VFS đang sử dụng (hỗ trợ cả "", "/", ".")
        val rootPaths = listOf("", "/", ".", "//", "\\", "\\\\")
        var validRootPath: String? = null

        for (p in rootPaths) {
            try {
                val entry = get(p)
                if (entry.isDirectory) {
                    val children = entry.list()
                    if (children != null) {
                        validRootPath = p
                        logger.info("APK Cleanup: Successfully resolved root path using '${if (p.isEmpty()) "[empty]" else p}' (${children.size} entries)")
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Hàm đệ quy quét qua toàn bộ cấu trúc VFS dựa trên root path đã tìm thấy
        fun scanAndClean(currentPath: String) {
            try {
                val dirEntry = get(currentPath)
                if (!dirEntry.isDirectory) return

                val children = dirEntry.list() ?: return
                for (childName in children) {
                    val childPath = if (currentPath.isEmpty() || currentPath == "." || currentPath == "/") {
                        childName
                    } else {
                        "$currentPath/$childName"
                    }

                    val entry = try { get(childPath) } catch (_: Exception) { continue }

                    if (entry.isDirectory) {
                        scanAndClean(childPath)
                        try {
                            if (entry.list().isNullOrEmpty()) {
                                delete(childPath)
                            }
                        } catch (_: Exception) {}
                    } else if (entry.isFile) {
                        if (isProtectedFile(childPath, childName)) continue
                        if (isJunkFile(childPath, childName)) {
                            val size = entry.length()
                            try {
                                delete(childPath)
                                removedFiles++
                                freedBytes += size
                                logger.info("Removed Junk: $childPath (${size}B)")
                            } catch (e: Exception) {
                                logger.warning("APK Cleanup: failed to delete $childPath: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warning("APK Cleanup: error scanning $currentPath: ${e.message}")
            }
        }

        if (validRootPath != null) {
            scanAndClean(validRootPath)
        } else {
            logger.warning("APK Cleanup: Could not dynamically resolve root path, falling back to default traversal.")
            scanAndClean("")
        }

        // 3. Dọn dẹp các cụm thư mục/file rác đặc thù cố định theo cấu trúc ReVanced
        fun removeTree(path: String) {
            try {
                val entry = get(path)
                if (entry.isDirectory) {
                    entry.list()?.forEach { child ->
                        removeTree("$path/$child")
                    }
                }
                delete(path)
            } catch (_: Exception) {}
        }

        try { removeTree("kotlin") } catch (_: Exception) {}
        try { removeTree("assets/audience_network.dex") } catch (_: Exception) {}
        try { removeTree("assets/audience_network") } catch (_: Exception) {}

        try {
            val metaInf = get("META-INF")
            if (metaInf.isDirectory) {
                metaInf.list()?.forEach { name ->
                    if (name.lowercase() == "services") return@forEach
                    removeTree("META-INF/$name")
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

        logger.info("APK Cleanup: successfully removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}