import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "9.0.0"
}

group = property("pluginGroup") as String
version = property("pluginVersion") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.extendedclip.com/releases/") { name = "placeholderapi" }
    maven("https://jitpack.io") { name = "jitpack" }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")

    // 선택 연동 (soft-depend). 서버에 없으면 훅이 로드되지 않습니다.
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }

    // 셰이딩 대상
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.4")

    // 순수 로직 단위 테스트 (StatLayout / StatValueParser / Stats)
    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks {
    processResources {
        filteringCharset = "UTF-8"
        val tokens = mapOf(
            "version" to project.version.toString(),
            "group" to project.group.toString(),
        )
        inputs.properties(tokens)
        filesMatching("plugin.yml") { expand(tokens) }
    }

    compileKotlin {
        // 빌드 재현성: 경고를 놓치지 않도록
        compilerOptions.extraWarnings.set(true)
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    shadowJar {
        archiveClassifier.set("")
        // Hikari 만 relocate. JDBC 드라이버는 드라이버 이름 문자열 로딩과 충돌하지 않도록 그대로 둡니다.
        relocate("com.zaxxer.hikari", "kr.inmc.titleforge.lib.hikari")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}
