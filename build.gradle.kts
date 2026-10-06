plugins {
    java
}

group = "com.iva"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.1.1 API (как на сервере: 26.1.1.build.N-...)
    compileOnly("io.papermc.paper:paper-api:26.1.1.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
