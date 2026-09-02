package dev.jkcarino.revanced.patches.all.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.shared.*
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