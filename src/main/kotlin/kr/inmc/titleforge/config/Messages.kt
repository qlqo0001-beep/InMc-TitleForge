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
        return Text.mini(template, *placeholders)
    }

    fun send(sender: CommandSender, path: String, vararg placeholders: Pair<String, Any?>) {
        val template = raw(path)
        if (template.isEmpty()) return
        sender.sendMessage(prefix().append(Text.mini(template, *placeholders)))
    }

    fun sendRaw(sender: CommandSender, component: Component) {
        sender.sendMessage(component)
    }

    private companion object {
        const val FILE_NAME = "messages.yml"
    }
}
