package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("LangCleanPatch")

private val KNOWN_NON_LANGUAGE_SEGMENTS = setOf("car", "any")

private data class LangQualifier(val lang: String, val region: String?)

private fun extractLanguageQualifiers(dirName: String): List<LangQualifier> {
    val segments = dirName.split("-")
    if (segments.size < 2) return emptyList()

    val rest = segments.drop(1)
    val result = mutableListOf<LangQualifier>()
    var i = 0

    while (i < rest.size) {
        val seg = rest[i]
        if (seg.startsWith("b+")) {
            val parts = seg.split("+")
            if (parts.size >= 2) {
                val lang = parts[1].lowercase()
                val region = parts.getOrNull(2)
                    ?.takeIf { it.length == 2 && it.all { c -> c.isUpperCase() } }
                    ?.lowercase()
                result.add(LangQualifier(lang, region))
            }
            i++
            continue
        }

        if (seg.length in 2..3 && seg.all { it.isLowerCase() } && seg !in KNOWN_NON_LANGUAGE_SEGMENTS) {
            val next = rest.getOrNull(i + 1)
            val isRegion = next != null && next.startsWith("r") && next.length == 3 &&
                next.drop(1).all { it.isUpperCase() }
            val region = if (isRegion) next!!.drop(1).lowercase() else null
            result.add(LangQualifier(seg, region))
            i += if (isRegion) 2 else 1
            continue
        }
        i++
    }
    return result
}

// Bổ sung getApkRoot cho LangClean
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

val langCleanPatch = resourcePatch(
    name = "Remove Languages",
    description = "Removes translations for languages you don\'t use.",
    use = false,
) {
    val keepLanguages by stringsOption(
        key = "keepLanguages",
        default = listOf("en", "vi"),
        title = "Languages to keep",
        description = "Exact resource variants to preserve.",
    )

    execute {
        val resDirRaw = get("res", false)
        val apkRoot = getApkRoot(resDirRaw)
        val resDir = File(apkRoot, "res") // Ép lấy res từ gốc thật

        if (!resDir.exists() || !resDir.isDirectory) {
            logger.warning("Language cleanup: res/ directory not found")
            return@execute
        }

        val keepSet: Set<Pair<String, String?>> = (keepLanguages ?: emptyList()).map { raw ->
            val parts = raw.split("-")
            val lang = parts[0].lowercase()
            val region = parts.getOrNull(1)
                ?.takeIf { it.length == 3 && it.startsWith("r", ignoreCase = true) }
                ?.drop(1)?.lowercase()
            lang to region
        }.toSet()

        var removedDirs = 0
        var keptDirs = 0

        resDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val qualifiers = extractLanguageQualifiers(dir.name)

            if (qualifiers.isEmpty()) {
                keptDirs++
                return@forEach
            }

            val shouldKeep = qualifiers.any { q -> (q.lang to q.region) in keepSet }

            if (shouldKeep) {
                keptDirs++
            } else {
                val filesToDelete = dir.walkTopDown().filter { it.isFile }.toList()
                filesToDelete.forEach { file ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                    try { delete(relativePath) } catch (_: Exception) {}
                }
                dir.deleteRecursively() // Xóa bằng File API
                removedDirs++
            }
        }

        logger.info("Language cleanup: kept $keptDirs dirs, removed $removedDirs dirs")
    }
}