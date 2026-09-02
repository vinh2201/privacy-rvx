package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File

private val DENSITIES = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
private val DRAWABLE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "gif")
private val MIPMAP_EXTENSIONS = setOf("png", "xml")

private fun groupedDensityDirs(resDir: File, prefix: String): Map<String, MutableMap<String, File>> {
    val groups = mutableMapOf<String, MutableMap<String, File>>()
    resDir.listFiles { f -> f.isDirectory && f.name.split("-").first() == prefix }?.forEach { dir ->
        val tokens = dir.name.split("-")
        val density = tokens.last()
        if (density !in DENSITIES) return@forEach
        val groupKey = tokens.dropLast(1).joinToString("-")
        groups.getOrPut(groupKey) { mutableMapOf() }[density] = dir
    }
    return groups
}

private fun dedupeByBaselineDensities(resDir: File, prefix: String, baselines: List<String>, extensions: Set<String>) {
    groupedDensityDirs(resDir, prefix).values.forEach { densityMap ->
        // 1. Gom tất cả tên file có trong các thư mục bác muốn giữ
        val baselineNames = mutableSetOf<String>()
        baselines.forEach { baseline ->
            densityMap[baseline]?.walkTopDown()
                ?.filter { it.isFile && it.extension.lowercase() in extensions }
                ?.map { it.name }
                ?.let { baselineNames.addAll(it) }
        }

        if (baselineNames.isEmpty()) return@forEach

        // 2. Đi dò các thư mục khác, không nằm trong danh sách giữ thì đem ra trảm
        densityMap.forEach { (density, dir) ->
            if (density in baselines) return@forEach // Né các thư mục mục tiêu ra
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in extensions && it.name in baselineNames }
                .forEach { it.delete() }
        }
    }
}

// DrawableCleanPatch.kt
val drawableCleanPatch = resourcePatch(
    name = "Remove Duplicate Graphics",
    description = "Keeps images for selected screen densities (e.g. xhdpi, xxhdpi) and removes copies for all other densities.",
    use = false,
) {
    // Đổi thành stringsOption (có s) và đổi tên biến cho chuẩn
    val targetDensities by stringsOption(
        key = "targetDensity", // Giữ nguyên key này để cmd cũ của bác vẫn chạy được
        default = null,
        title = "Target densities",
        description = "Density buckets to keep; duplicates are stripped from every other bucket.",
    )

    execute {
        val resDir = get("res", false)
        
        // Xử lý chuỗi từ CLI: Lọc bỏ ngoặc, ngoặc kép, khoảng trắng rồi tách bằng dấu phẩy
        val baselines = (targetDensities ?: emptyList())
            .flatMap { it.replace("[", "").replace("]", "").replace("\"", "").split(",") }
            .map { it.trim().lowercase() }
            .filter { it in DENSITIES }
            .takeIf { it.isNotEmpty() } ?: listOf("xxhdpi") // Nếu lỗi thì tự fallback về xxhdpi

        dedupeByBaselineDensities(resDir, "drawable", baselines, DRAWABLE_EXTENSIONS)
        dedupeByBaselineDensities(resDir, "mipmap", baselines, MIPMAP_EXTENSIONS)

        resDir.walkBottomUp()
            .filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }
    }
}