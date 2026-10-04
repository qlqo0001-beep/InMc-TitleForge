package kr.inmc.titleforge.display

import kr.inmc.core.integration.PlayerSettings
import org.bukkit.Material

/**
 * 타이틀포지가 core 개인 설정 창구에 올리는 것 — 플레이어 메뉴의 개인 설정 화면에 보인다.
 * 끈 건 안 보인다. 정의가 없을 때(core 가 옛 판) 기본은 켜짐이라 지금과 같다.
 */
internal object TitleForgeSettings {

    const val OWNER = "칭호"

    /** 본인 닉네임 표시. 끄면 실명이 나온다. */
    const val SHOW_NICKNAME = "titleforge.show-nickname"

    /** 본인 칭호 표시. 끄면 미장착 문구가 나온다. */
    const val SHOW_TITLE = "titleforge.show-title"

    /** 본인 인장 표시. 끄면 미장착 문구가 나온다. */
    const val SHOW_SEAL = "titleforge.show-seal"

    /** 타인 인장 끄기. 켜면 남의 공유 이름표(인장 줄)가 안 보인다. */
    const val HIDE_OTHERS_SEAL = "titleforge.hide-others-seal"

    fun register() {
        PlayerSettings.register(
            PlayerSettings.Setting(
                SHOW_NICKNAME, OWNER, "내 닉네임 표시", Material.NAME_TAG,
                listOf("끄면 다른 사람에게 실명으로 보입니다."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                SHOW_TITLE, OWNER, "내 칭호 표시", Material.GOLDEN_HELMET,
                listOf("끄면 다른 사람에게 칭호 없이 보입니다."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                SHOW_SEAL, OWNER, "내 인장 표시", Material.NETHER_STAR,
                listOf("끄면 다른 사람에게 인장 없이 보입니다."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                HIDE_OTHERS_SEAL, OWNER, "타인 인장 끄기", Material.ENDER_EYE,
                listOf("켜면 다른 사람의 공유 이름표(인장 줄)가 안 보입니다."),
                PlayerSettings.Toggle(false),
            ),
        )
    }

    fun unregister() = PlayerSettings.unregisterAll(OWNER)
}
