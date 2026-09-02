package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

/*
 * Adapted from ReVanced:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/misc/privacy/DisableSentryTelemetry.kt
 */
@Suppress("unused")
val disableSentryTelemetryPatch = resourcePatch(
    name = "Disable Sentry telemetry (privacy)",
    description = "Disables Sentry telemetry by turning off SDK auto-init and clearing the DSN.",
    use = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.childrenNamed("application").single() as Element

            application.setApplicationMetaData("io.sentry.enabled", "false")
            application.setApplicationMetaData("io.sentry.dsn", "")

            val disabledComponents = application.disableComponentsWhere { name ->
                name.startsWith("io.sentry.") || name.contains(".Sentry")
            }

            println("Disable Sentry telemetry: disabled $disabledComponents manifest components.")
        }
    }
}

// --- Helper extensions gom chung vào 1 file ---

private fun Element.childrenNamed(name: String): List<Element> {
    val nodes = childNodes
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.nodeName == name) add(node)
        }
    }
}

private fun Element.childrenNamed(vararg names: String): List<Element> {
    val acceptedNames = names.toSet()
    val nodes = childNodes
    return buildList {
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.nodeName in acceptedNames) add(node)
        }
    }
}

private fun Element.getOrCreateApplicationMetaData(name: String): Element {
    childrenNamed("meta-data")
        .firstOrNull { it.getAttribute("android:name") == name }
        ?.let { return it }

    val metaData = ownerDocument.createElement("meta-data")
    metaData.setAttribute("android:name", name)
    appendChild(metaData)
    return metaData
}

private fun Element.setApplicationMetaData(name: String, value: String) {
    getOrCreateApplicationMetaData(name).setAttribute("android:value", value)
}

private fun Element.disableComponentsWhere(predicate: (String) -> Boolean): Int {
    var disabled = 0

    childrenNamed("activity", "provider", "service", "receiver")
        .filter { component -> predicate(component.getAttribute("android:name")) }
        .forEach { component ->
            component.setAttribute("android:enabled", "false")
            component.setAttribute("android:exported", "false")
            disabled++
        }

    return disabled
}