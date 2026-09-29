plugins {
    id("inmc.paper-plugin")
}

group = "kr.inmc.titleforge"
version = "1.0.0"

inmc {
    // 3세대에서 26.1.2 로 컴파일하면서 plugin.yml 에는 api-version '1.21' 을 적고 있었다.
    // 관례 플러그인이 api-version 을 여기서 유도하므로 그 어긋남이 구조적으로 불가능해진다.
    paper = "26.1.2"
    pluginName = "InMc-TitleForge"
}

dependencies {
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    // MMOItems / MythicLib 는 100% 리플렉션.

    // 셰이딩 대상. 이 넷만 jar 에 들어간다 (stdlib 은 core 가 준다).
    implementation(libs.bstats.bukkit)
    implementation(libs.hikaricp)
    // JDBC 드라이버는 컴파일 시점에 보이지 않는다 — 연결 URL 로만 잡힌다.
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.mariadb.client)
}

tasks.shadowJar {
    // Hikari 만 relocate. JDBC 드라이버는 드라이버 이름 문자열 로딩과 충돌하지 않도록 그대로 둔다.
    relocate("com.zaxxer.hikari", "kr.inmc.titleforge.lib.hikari")
    // bStats 는 relocate 가 **필수**다. 안 하면 다른 플러그인의 bStats 와 충돌하고,
    // 라이브러리 자체가 relocate 여부를 검사해 예외를 던진다.
    relocate("org.bstats", "kr.inmc.titleforge.lib.bstats")
}
