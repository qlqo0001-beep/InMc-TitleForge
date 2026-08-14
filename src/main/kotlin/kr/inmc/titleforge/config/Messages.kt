package kr.inmc.titleforge.config

import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * messages.yml 기반 메시지 번들.
 * 유저에게 보이는 문장은 전부 여기를 통한다 (맞춤 지침 7.3-11).
 */
class Messages(private val plugin: Plugin) {

    private var config: YamlConfiguration = YamlConfiguration()
    private var defaults: YamlConfiguration = YamlConfiguration()
    private var prefixRaw: String = ""

    /**
     * 어느 메시지에서나 쓸 수 있는 공용 토큰.
     *
     * 호출부가 넘기지 않아도 `<allowed>` 같은 값을 messages.yml 어디에서든 쓸 수 있게 한다.
     * 값은 [Settings] 스냅샷에서 오므로 reload 때만 바뀐다 — 매 호출 계산이 없다.
     */
    private var globals: Array<Pair<String, Any?>> = emptyArray()

    /** reload 시점에 [kr.inmc.titleforge.TitleForgePlugin] 이 갱신한다. */
    fun setGlobals(values: Map<String, Any?>) {
        globals = values.map { it.key to it.value }.toTypedArray()
    }

    /**
     * 공용 토큰과 호출부 토큰을 합친다.
     *
     * MiniMessage 의 [net.kyori.adventure.text.minimessage.tag.resolver.TagResolver] 는
     * 같은 이름이 겹칠 때 **나중에 등록된 것이 이긴다**(TextPlaceholderTest 로 고정).
     * 그래서 공용 토큰을 앞에 두고 호출부 인자를 뒤에 둬, 호출부가 항상 덮어쓰게 한다.
     */
    private fun render(template: String, placeholders: Array<out Pair<String, Any?>>): Component =
        if (globals.isEmpty()) Text.mini(template, *placeholders)
        else Text.mini(template, *globals, *placeholders)

    fun reload() {
        val file = File(plugin.dataFolder, FILE_NAME)
        if (!file.exists()) plugin.saveResource(FILE_NAME, false)
        config = YamlConfiguration.loadConfiguration(file)

        plugin.getResource(FILE_NAME)?.use { stream ->
            defaults = YamlConfiguration.loadConfiguration(
                InputStreamReader(stream, StandardCharsets.UTF_8),
            )
        }
        prefixRaw = raw("prefix")
    }

    fun raw(path: String): String = config.getString(path) ?: defaults.getString(path) ?: ""

    fun list(path: String): List<String> = config.getStringList(path)
        .ifEmpty { defaults.getStringList(path) }

    fun prefix(): Component = Text.mini(prefixRaw)

    /** 접두사 없는 컴포넌트. GUI 등에서 사용. */
    fun component(path: String, vararg placeholders: Pair<String, Any?>): Component {
        val template = raw(path)
        if (template.isEmpty()) return Component.text(path)
        return render(template, placeholders)
    }

    fun send(sender: CommandSender, path: String, vararg placeholders: Pair<String, Any?>) {
        val template = raw(path)
        if (template.isEmpty()) return
        sender.sendMessage(prefix().append(render(template, placeholders)))
    }

    fun sendRaw(sender: CommandSender, component: Component) {
        sender.sendMessage(component)
    }

    private companion object {
        const val FILE_NAME = "messages.yml"
    }
}
