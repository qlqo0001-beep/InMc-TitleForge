package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.badge.TitleStyle
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 칭호 꾸미기 — 괄호 모양(둘째 줄) · 괄호 색(셋째 줄) · 글자 색 표(넷·다섯째 줄 — 좌클릭 시작 색, 우클릭 끝 색 = 그라데이션) · 글자 · 굵게.
 * 맨 위에 미리 보기, [적용]을 눌러야 칭호에 들어간다(그 전에는 이 창의 것). 테섭 요청 2026-10-02 — "괄호·색을 표에서 골라 편하게".
 */
class TitleDecorateMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val badgeId: String,
    private var style: TitleStyle,
) : Menu(plugin, viewer, rows = 6) {

    override fun title(): Component = Text.mini("<dark_gray>칭호 꾸미기: $badgeId")

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        button(SLOT_PREVIEW, Items.of(Material.NAME_TAG, Text.mini(style.toMini()), listOf(
            Text.mini("<gray>미리 보기 — [적용]을 눌러야 칭호에 들어갑니다."),
            Text.mini("<dark_gray>" + Text.escape(style.toMini())),
        )))

        for ((index, bracket) in TitleStyle.Bracket.entries.withIndex()) {
            val selected = bracket == style.bracket
            button(BRACKET_ROW + index, Items.of(Material.PAPER, Text.mini((if (selected) "<green>▶ " else "<white>") + Text.escape(bracket.label)),
                listOf(Text.mini("<gray>괄호 모양")), glow = selected)) {
                style = style.copy(bracket = bracket)
                redraw()
            }
        }
        for ((index, swatch) in BRACKET_COLORS.withIndex()) {
            val selected = swatch.hex.equals(style.bracketColor, ignoreCase = true)
            button(BRACKET_COLOR_ROW + index, Items.of(swatch.material, Text.mini("<color:${swatch.hex}>${swatch.name}</color>"),
                listOf(Text.mini("<gray>괄호 색"), Text.mini("<dark_gray>${swatch.hex}")), glow = selected)) {
                style = style.copy(bracketColor = swatch.hex)
                redraw()
            }
        }
        for ((index, swatch) in TEXT_COLORS.withIndex()) {
            val isStart = swatch.hex.equals(style.start, ignoreCase = true)
            val isEnd = swatch.hex.equals(style.end, ignoreCase = true)
            val lore = buildList {
                add(Text.mini("<gray>글자 색 <dark_gray>${swatch.hex}"))
                if (isStart) add(Text.mini("<green>시작 색"))
                if (isEnd) add(Text.mini("<aqua>끝 색"))
                add(Component.empty())
                add(Text.mini("<yellow>▶ 좌클릭: 시작 색(단색이면 이 색)"))
                add(Text.mini("<yellow>▶ 우클릭: 끝 색 — 그라데이션"))
            }
            button(TEXT_COLOR_ROW + index, Items.of(swatch.material, Text.mini("<color:${swatch.hex}>${swatch.name}</color>"), lore, glow = isStart || isEnd)) { event ->
                style = if (event.isRightClick) style.copy(end = swatch.hex) else style.copy(start = swatch.hex)
                redraw()
            }
        }

        button(SLOT_TEXT, Items.of(Material.WRITABLE_BOOK, Text.mini("<yellow>글자"), listOf(
            Text.mini("<gray>지금: <white>" + Text.escape(style.text)), Component.empty(), Text.mini("<yellow>▶ 클릭: 바꾸기")))) {
            val prompt = plugin.messages.prefix().append(Text.mini("<gray>칭호 글자를 입력하세요(괄호·색 없이)."))
            plugin.dialogInput.prompt(viewer, style.text, prompt) { input ->
                if (input != null && input.isNotBlank()) style = style.copy(text = input.trim())
                openLater()
            }
        }
        button(SLOT_BOLD, Items.of(if (style.bold) Material.IRON_INGOT else Material.IRON_NUGGET,
            Text.mini("<yellow>굵게: " + if (style.bold) "<green>켜짐" else "<red>꺼짐"), glow = style.bold)) {
            style = style.copy(bold = !style.bold)
            redraw()
        }
        button(SLOT_GRADIENT, Items.of(if (style.end != null) Material.PRISMARINE_SHARD else Material.QUARTZ,
            Text.mini(if (style.end != null) "<aqua>그라데이션 — 클릭: 단색으로" else "<white>단색 <gray>(색 표를 우클릭하면 그라데이션)"))) {
            style = style.copy(end = null)
            redraw()
        }
        button(SLOT_APPLY, Items.of(Material.LIME_CONCRETE, Text.mini("<green>✔ 적용"), listOf(Text.mini("<gray>이 모양을 칭호의 표시 이름으로 저장합니다.")))) {
            val current = plugin.badges.get(BadgeType.TITLE, badgeId) ?: return@button
            plugin.badgeService.persist(current.copy(displayName = style.toMini()))
            plugin.messages.send(viewer, "badge.edited", "type" to current.type.display, "id" to current.id, "field" to "이름")
            BadgeEditMenu(plugin, viewer, BadgeType.TITLE, badgeId).openLater()
        }
        button(SLOT_BACK, Items.of(Material.OAK_DOOR, Text.mini("<gray>◀ 뒤로 <dark_gray>(적용하지 않음)"))) {
            BadgeEditMenu(plugin, viewer, BadgeType.TITLE, badgeId).openLater()
        }
        fill()
    }

    private class Swatch(val name: String, val hex: String, val material: Material)

    companion object {
        const val SLOT_PREVIEW = 4
        const val BRACKET_ROW = 9
        const val BRACKET_COLOR_ROW = 18
        const val TEXT_COLOR_ROW = 27
        const val SLOT_BACK = 45
        const val SLOT_TEXT = 47
        const val SLOT_BOLD = 48
        const val SLOT_GRADIENT = 49
        const val SLOT_APPLY = 51

        private val BRACKET_COLORS = listOf(
            Swatch("흰색", "#FFFFFF", Material.WHITE_WOOL), Swatch("회색", "#AAAAAA", Material.LIGHT_GRAY_WOOL),
            Swatch("짙은 회색", "#555555", Material.GRAY_WOOL), Swatch("금색", "#FFAA00", Material.ORANGE_WOOL),
            Swatch("노랑", "#FFFF55", Material.YELLOW_WOOL), Swatch("하늘", "#55FFFF", Material.LIGHT_BLUE_WOOL),
            Swatch("빨강", "#FF5555", Material.RED_WOOL), Swatch("분홍보라", "#FF55FF", Material.MAGENTA_WOOL),
            Swatch("연두", "#55FF55", Material.LIME_WOOL),
        )

        private val TEXT_COLORS = listOf(
            Swatch("흰색", "#FFFFFF", Material.WHITE_CONCRETE), Swatch("회색", "#AAAAAA", Material.LIGHT_GRAY_CONCRETE),
            Swatch("짙은 회색", "#555555", Material.GRAY_CONCRETE), Swatch("빨강", "#FF5555", Material.RED_CONCRETE),
            Swatch("진홍", "#AA0000", Material.NETHER_WART_BLOCK), Swatch("금색", "#FFAA00", Material.ORANGE_CONCRETE),
            Swatch("노랑", "#FFFF55", Material.YELLOW_CONCRETE), Swatch("연두", "#55FF55", Material.LIME_CONCRETE),
            Swatch("초록", "#00AA00", Material.GREEN_CONCRETE),
            Swatch("하늘", "#55FFFF", Material.LIGHT_BLUE_CONCRETE), Swatch("청록", "#00AAAA", Material.CYAN_CONCRETE),
            Swatch("파랑", "#5555FF", Material.BLUE_CONCRETE), Swatch("남색", "#0000AA", Material.LAPIS_BLOCK),
            Swatch("분홍보라", "#FF55FF", Material.MAGENTA_CONCRETE), Swatch("보라", "#AA00AA", Material.PURPLE_CONCRETE),
            Swatch("분홍", "#FFB6C1", Material.PINK_CONCRETE), Swatch("민트", "#98FFC8", Material.PRISMARINE_BRICKS),
            Swatch("살구", "#FFCBA4", Material.TERRACOTTA),
        )
    }
}
