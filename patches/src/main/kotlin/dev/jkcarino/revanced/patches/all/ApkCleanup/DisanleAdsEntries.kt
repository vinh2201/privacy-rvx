package dev.jkcarino.revanced.patches.all.apkcleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import java.io.File

@Suppress("unused")
val universalRemoveAdManifestEntriesPatch = resourcePatch(
    name = "Remove ad manifest entries",
    description = "Removes common ad SDK permissions, services, providers, libraries, and metadata.",
    default = false,
) {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val adPermissions = setOf(
                "com.google.android.gms.permission.AD_ID",
                "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
                "android.permission.ACCESS_ADSERVICES_AD_ID",
                "android.permission.ACCESS_ADSERVICES_TOPICS",
                "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE",
                "android.permission.AD_SERVICES_CONFIG",
                "android.permission.KILL_BACKGROUND_PROCESSES",
            )
            val adNames = listOf(
                "com.google.android.gms.ads",
                "com.google.android.gms.measurement",
                "com.facebook.ads",
                "com.facebook.adspayments",
                "com.facebook.adscomposer",
                "com.facebook.adsconsentvalue",
                "com.facebook.bugreporter",
                "com.facebook.adinterfaces",
                "com.facebook.adspreviewinjection",
                "com.facebook.adpreview",
                "com.facebook.adsexperiencetool",
                "com.facebook.messaging.business.ads",
                "com.facebook.messaging.nativepagereply.adcreation",
                "com.facebook.messaging.professionalmode.adscreation",
                "com.applovin",
                "com.mbridge.msdk",
                "com.inmobi.ads",
                "com.unity3d.ads",
                "com.vungle",
                "com.ironsource",
                "com.bytedance.sdk",
                "com.anythink",
                "com.qq.e.",
                "com.baidu.mobads",
                "com.kwad.sdk",
                "com.sigmob",
                "com.tradplus",
                "com.pangle",
            )
            for (tag in listOf("uses-permission", "uses-library", "property", "meta-data", "provider", "service", "receiver", "activity")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in nodes.length - 1 downTo 0) {
                    val node = nodes.item(i) as? Element ?: continue
                    val name = node.getAttribute("android:name")
                    val value = node.getAttribute("android:value")
                    val remove = when (tag) {
                        "uses-permission" -> name in adPermissions
                        "uses-library" -> name == "android.ext.adservices"
                        "property" -> name == "android.adservices.AD_SERVICES_CONFIG"
                        else -> adNames.any { name.startsWith(it) || value.startsWith(it) }
                    }
                    if (remove) node.parentNode?.removeChild(node)
                }
            }
        }
    }
}

@Suppress("unused")
val universalDisableAdSdkCallsPatch = bytecodePatch(
    name = "Disable ad SDK calls",
    description = "No-ops common ad SDK load/show/init/fetch methods in bundled ad packages.",
    use = false,
) {
    execute {
        val adPackages = listOf(
            "Lcom/applovin/",
            "Lcom/facebook/ads/",
            "Lcom/fyber/inneractive/sdk/",
            "Lcom/google/android/gms/ads/",
            "Lcom/mbridge/msdk/",
            "Lcom/inmobi/ads/",
            "Lcom/smaato/sdk/",
            "Lcom/tradplus/ads/",
            "Lcom/unity3d/ads/",
            "Lcom/unity3d/services/",
            "Lcom/vungle/",
            "Lcom/ironsource/",
            "Lcom/bytedance/sdk/",
            "Lcom/anythink/",
            "Lcom/qq/e/",
            "Lcom/baidu/mobads/",
            "Lcom/kwad/sdk/",
            "Lcom/sigmob/",
            "Lcom/pangle/",
        )
        val voidMethodNames = setOf(
            "loadAd",
            "loadAds",
            "load",
            "show",
            "showAd",
            "fetchAd",
            "init",
            "start",
            "initSDK",
            "initialize",
            "initializeSdk",
            "loadSplashAd",
            "loadRewardVideoAd",
            "loadInterstitialAd",
            "loadBannerAd",
            "loadNativeAd",
            "loadFeedAd",
            "loadNativeExpressAd",
            "loadBannerExpressAd",
            "loadDrawFeedAd",
            "loadExpressDrawFeedAd",
            "loadSplashScreenAd",
            "showSplashView",
            "showSplashClickEyeView",
            "showSplashCardView",
            "showRewardVideoAd",
            "showFullScreenVideoAd",
            "showInterstitialAd",
            "showSplashMiniWindow",
            "showSplashMiniWindowIfNeeded",
            "showNativeAd",
            "negativeFeedback",
            "startLoadAd",
        )
        val objectMethodNames = setOf(
            "getSplashView",
            "getSplashClickEyeView",
            "getSplashCardView",
            "getBannerView",
            "getFeedView",
        )
        classDefForEach { classDef ->
            if (adPackages.none { classDef.type.startsWith(it) }) return@classDefForEach
            mutableClassDefBy(classDef).methods.forEach { method ->
                if (method.name in voidMethodNames && method.returnType == "V" && method.name !in setOf("<init>", "<clinit>")) {
                    method.addInstructions(0, "return-void")
                }
                if (method.name in objectMethodNames && method.returnType.startsWith("L")) {
                    method.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
                }
                if (method.name in setOf("isInitSuccess", "isSdkReady") && method.returnType == "Z") {
                    method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                }
            }
        }
    }
}