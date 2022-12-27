import java.util.Date

/*
 * Copyright (C) 2022 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    dependencies {
        classpath("org.junit.platform:junit-platform-gradle-plugin:1.2.0")
        classpath("gradle.plugin.com.github.johnrengelman:shadow:7.1.2")
    }
}

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "1.7.22"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

repositories {
    mavenCentral()
}

group = "com.ruesga.dash.wallclock"
version = "0.1-SNAPSHOT"

apply(plugin = "java")
apply(plugin = "org.jetbrains.kotlin.jvm")

dependencies {
    val implementation by configurations
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("io.github.humbleui:skija-macos-x64:0.106.0")
    implementation("org.apache.tika:tika-core:2.6.0")
    implementation("commons-cli:commons-cli:1.5.0")
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] = archiveVersion
        attributes["Built-Date"] = Date()
        attributes["Built-JDK"] = System.getProperty("java.version")
        attributes["Main-Class"] = "com.ruesga.dash.wallclock.WallClock"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks {
    "build" {
        dependsOn(shadowJar)
    }
}