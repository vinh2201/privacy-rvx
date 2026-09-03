package dev.jkcarino.revanced.patches.google.gboard.featureflags

import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.stringsOption
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jkcarino.revanced.patches.google.gboard.detection.signature.bypassSignaturePatch
import java.util.logging.Logger

@Suppress("unused")
val toggleFeatureFlagsPatch = bytecodePatch(
    name = "Toggle feature flags",
    description = "Toggles Gboard feature flags to enable or disable experimental or hidden features.",
    use = false,
) {
    dependsOn(bypassSignaturePatch)

    compatibleWith("com.google.android.inputmethod.latin")

    val logger = Logger.getLogger(this::class.java.name)

    // Option 1: Danh sách các cờ muốn BẬT (Kế thừa list mặc định cũ)
    val flagsToEnable by stringsOption(
        key = "flagsToEnable",
        default = listOf("enable_number_row", "enable_settings_search", "enable_extended_clipboard_history", "require_device_idle_for_content_cache_download", "disable_refresh_in_result_page_emoji_kitchen_browse_search", "more_pill_keys", "enable_semantic_emoji", "enable_fallback_for_emoji_search_server_error"),
        title = "Flags to Enable",
        description = "List of Gboard feature flags to forcefully ENABLE.",
        required = false // Đổi thành false để không bắt buộc phải nhập nếu chỉ muốn tắt
    ) { flags ->
        val flagsRegex = """^[A-Za-z0-9_-]+$""".toRegex()
        flags.isNullOrEmpty() || flags.all { it.matches(flagsRegex) }
    }

    // Option 2: Danh sách các cờ muốn TẮT (Mặc định để trống)
    val flagsToDisable by stringsOption(
        key = "flagsToDisable",
        default = listOf("brella", "brella_clearcut_log", "enable_training_cache_metrics_processors", "enable_split_layout_promo", "enable_full_width_layout_promo", "nga_enable_data_collection_banner", "enable_conversation_id_in_training_cache", "enable_chinese_training_cache", "enable_perfetto_trigger", "access_point_feature_promote_banner", "access_points_customization_banner", "agentic_dictation_promo_banner", "agentic_dictation_enable_promo_banner", "apostrophe_behavior_promo_banner", "auto_translate_banner", "enable_hmm_on_device_logging", "enable_biasing_metrics_logging", "log_spell_checker_metrics_v2", "log_spell_checker_suggestion_language", "enable_crowdsource_integration", "enable_data_share_service", "enable_voice_donation_flow", "enable_backup_delight5_personalized_data", "enable_writing_tools_v1_training_cache", "__bytes__dynamic_federated_trainer_population_list"),
        title = "Flags to Disable",
        description = "List of Gboard feature flags to forcefully DISABLE.",
        required = false
    ) { flags ->
        val flagsRegex = """^[A-Za-z0-9_-]+$""".toRegex()
        flags.isNullOrEmpty() || flags.all { it.matches(flagsRegex) }
    }

    execute {
        // Gom đoạn logic gốc đang chạy mượt của bác vào 1 hàm để tái sử dụng
        fun processFlags(flags: List<String>?, enable: Boolean) {
            if (flags.isNullOrEmpty()) return

            flags.forEach { flag ->
                val fingerprint = featureFlagFingerprint(flag.trim())

                runCatching {
                    fingerprint.method.apply {
                        val isEnabledIndex = fingerprint.patternMatch!!.endIndex
                        val isEnabledInstruction =
                            getInstruction<OneRegisterInstruction>(isEnabledIndex)
                        val isEnabledRegister = isEnabledInstruction.registerA
                        
                        // Nếu enable = true -> 0x1, enable = false -> 0x0
                        val enabled = if (enable) "0x1" else "0x0"

                        replaceInstruction(
                            index = isEnabledIndex,
                            smaliInstruction = "const/4 v$isEnabledRegister, $enabled"
                        )
                    }
                }.onSuccess {
                    val state = if (enable) "on" else "off"
                    logger.info("[Found] \"$flag\" toggled $state.")
                }.onFailure {
                    logger.info("[Skipped] \"$flag\" was not found. No changes applied.")
                }
            }
        }

        // Chạy mẻ 1: Xử lý đống cờ cần BẬT
        processFlags(flagsToEnable, true)
        
        // Chạy mẻ 2: Xử lý đống cờ cần TẮT
        processFlags(flagsToDisable, false)
    }
}