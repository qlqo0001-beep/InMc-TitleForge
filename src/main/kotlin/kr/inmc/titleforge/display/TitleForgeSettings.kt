package kr.inmc.titleforge.display

import kr.inmc.core.integration.PlayerSettings
import org.bukkit.Material

/**
 * 타이틀포지가 core 개인 설정 창구에 올리는 것 — 플레이어 메뉴의 개인 설정 화면에 보인다.
 *
 * 전부 **본인 화면** 기준이다. 타인에게 보이는 것은 바뀌지 않는다 — 3D 이름표는 공유 엔티티라
 * 보는 사람마다 다른 글자를 보여줄 수 없고, 가림(hideEntity)만 뷰어별로 된다.
 * 탭·채팅·PAPI는 전역 설정 그대로다.
 */
internal object TitleForgeSettings {

    const val OWNER = "칭호"

    /**
     * 본인 공유 이름표(인장 줄)를 본인에게 보여주기. 기본 ON(지금까지 그대로).
     * 끄면 본인에게 인장 줄이 안 보인다.
     */
    const val SHOW_SEAL = "titleforge.show-seal"

    /**
     * 본인 타인용 이름표(칭호·닉네임 줄)를 본인에게 보여주기. 기본 OFF(지금까지 그대로 —
     * 남에게 보이는 줄이라 본인에게는 숨겼다). 켜면 본인도 본다.
     *
     * 줄이 하나라 칭호·닉네임은 함께 보이거나 함께 안 보인다. 낱개로 가리려면 줄 설정을 나눠야 한다.
     */
    const val SHOW_NAME = "titleforge.show-name"

    /** 타인 인장 끄기. 켜면 남의 공유 이름표(인장 줄)가 안 보인다. 본인 것은 그대로. */
    const val HIDE_OTHERS_SEAL = "titleforge.hide-others-seal"

    fun register() {
        PlayerSettings.register(
            PlayerSettings.Setting(
                SHOW_SEAL, OWNER, "내 인장 보기", Material.NETHER_STAR,
                listOf("끄면 내 화면에 내 인장 줄이 안 보입니다.", "남에게 보이는 것은 그대로입니다."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                SHOW_NAME, OWNER, "칭호,닉네임 보기", Material.NAME_TAG,
                listOf("켜면 내 화면에 내 칭호·닉네임 줄이 보입니다."),
                PlayerSettings.Toggle(false),
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
