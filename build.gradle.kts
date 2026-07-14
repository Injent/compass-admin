
import io.ktor.plugin.features.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "ru.injent"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

val prepareJibExtra by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("jib-extra/app"))
    from("templates") {
        into("templates")
    }
    from("static") {
        into("static")
    }
    from("system_instructions.txt")
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("compassadmin")
        imageTag.set(project.version.toString())
        portMappings.set(
            listOf(
                DockerPortMapping(
                    outsideDocker = 8080,
                    insideDocker = 8080,
                    protocol = DockerPortMappingProtocol.TCP,
                )
            )
        )
    }
}

jib {
    container {
        mainClass = application.mainClass.get()
        ports = listOf("8080")
        environment = mapOf("COMPASS_DB_PATH" to "/app/data/compassadmin.db")
        workingDirectory = "/app"
        volumes = listOf("/app/google", "/app/tokens", "/app/data")
    }
    extraDirectories {
        paths {
            path {
                setFrom(layout.buildDirectory.dir("jib-extra"))
                into = "/"
            }
        }
    }
}

tasks.named("jibBuildTar") {
    dependsOn(prepareJibExtra)
}

tasks.named("jibDockerBuild") {
    dependsOn(prepareJibExtra)
}

tasks.named("jib") {
    dependsOn(prepareJibExtra)
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.doubleReceive)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.freemarker)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.server.sse)
    implementation(ktorLibs.server.swagger)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)
    implementation(libs.logback.classic)

    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.client.json)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.serialization)

    implementation(libs.google.auth)
    implementation(libs.google.apiClient)
    implementation(libs.google.drive)
    implementation(libs.google.sheets)

    implementation(platform(libs.exposed.bom))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    implementation(libs.gigachat)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

val compileKotlin: KotlinCompile by tasks

compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xexplicit-backing-fields", "-Xcontext-parameters"))
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES")

    minimize {
        r8 {
            keepRules.addAll(
                listOf(
                    "-keep class ru.injent.AppKt { public static void configureApp(io.ktor.server.application.Application); }",
                    "-dontwarn io.netty.internal.tcnative.**",
                    "-dontwarn jakarta.servlet.**",
                    "-dontwarn lombok.Generated",
                    "-dontwarn org.apache.log4j.**",
                    "-dontwarn org.apache.logging.log4j.**",
                    "-dontwarn org.apache.xml.utils.**",
                    "-dontwarn org.bouncycastle.**",
                    "-dontwarn org.conscrypt.**",
                    "-dontwarn org.jaxen.**",
                    "-dontwarn org.python.**",
                    "-dontwarn org.zeroturnaround.javarebel.**",
                    "-dontwarn reactor.blockhound.**",
                )
            )
            enableObfuscation()
            enableOptimization()
        }
    }
}
