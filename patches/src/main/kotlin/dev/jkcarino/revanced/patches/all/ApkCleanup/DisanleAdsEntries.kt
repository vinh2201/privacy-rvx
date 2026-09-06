package dev.revanced.patches.ads // Đổi tên package theo project ReVanced của bạn

import app.revanced.patcher.data.AndroidManifestContext
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.patch.androidManifestPatch
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import org.w3c.dom.Element

object RemoveAdManifestEntriesPatch = androidManifestPatch(
    name = "Remove ad manifest entries",
    description = "Removes common ad SDK permissions, services, providers, libraries, and metadata.",
    use = false
) {
    override fun execute(context: AndroidManifestContext) {
        val doc = context.document
        val adPermissions = setOf(
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_TOPICS",
            "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE",
            "android.permission.AD_SERVICES_CONFIG",
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
                if (remove) {
                    node.parentNode?.removeChild(node)
                }
            }
        }
    }
}

object DisableAdSdkCallsPatch = bytecodePatch(
    name = "Disable ad SDK calls",
    description = "No-ops common ad SDK load/show/init/fetch methods in bundled ad packages.",
    use = false
) {
    override fun execute(context: BytecodeContext) {
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
            "loadAd", "loadAds", "load", "show", "showAd", "fetchAd", "init", "start",
            "initSDK", "initialize", "initializeSdk", "loadSplashAd", "loadRewardVideoAd",
            "loadInterstitialAd", "loadBannerAd", "loadNativeAd", "loadFeedAd",
            "loadNativeExpressAd", "loadBannerExpressAd", "loadDrawFeedAd",
            "loadExpressDrawFeedAd", "loadSplashScreenAd", "showSplashView",
            "showSplashClickEyeView", "showSplashCardView", "showRewardVideoAd",
            "showFullScreenVideoAd", "showInterstitialAd", "showSplashMiniWindow",
            "showSplashMiniWindowIfNeeded", "showNativeAd", "negativeFeedback", "startLoadAd"
        )
        val objectMethodNames = setOf(
            "getSplashView", "getSplashClickEyeView", "getSplashCardView",
            "getBannerView", "getFeedView"
        )

        context.classes.forEach { classDef ->
            if (adPackages.none { classDef.type.startsWith(it) }) return@forEach

            classDef.methods.forEach methodLoop@{ method ->
                if (method.name in setOf("<init>", "<clinit>")) return@methodLoop

                val implementation = method.implementation ?: return@methodLoop

                val isVoid = method.name in voidMethodNames && method.returnType == "V"
                val isObject = method.name in objectMethodNames && method.returnType.startsWith("L")
                val isBool = method.name in setOf("isInitSuccess", "isSdkReady") && method.returnType == "Z"

                if (!isVoid && !isObject && !isBool) return@methodLoop

                // Đảm bảo method có ít nhất 1 register để return v0 không bị văng Exception
                if (!isVoid && implementation.registerCount < 1) {
                    implementation.registerCount = 1
                }

                if (isVoid) {
                    implementation.instructions.add(0, BuilderInstruction10x(Opcode.RETURN_VOID))
                } else if (isObject) {
                    implementation.instructions.add(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    implementation.instructions.add(1, BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
                } else if (isBool) {
                    implementation.instructions.add(0, BuilderInstruction11n(Opcode.CONST_4, 0, 0))
                    implementation.instructions.add(1, BuilderInstruction11x(Opcode.RETURN, 0))
                }
            }
        }
    }
}