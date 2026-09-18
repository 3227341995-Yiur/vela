// The Vela plugin for IntelliJ IDEA.
//
// It is deliberately *thin*: the language's own compiler (`selfhost/build/vm.exe`
// 鈥?Vela, compiled to C) is the only thing that decides what a program means, and
// this plugin never grows a second opinion.  What it adds is what an editor owes a
// programmer: the file type, highlighting, and diagnostics that come from the real
// compiler, plus one action that builds and runs.
//
//     ./gradlew buildPlugin        # build/distributions/vela-idea-plugin-<v>.zip
//     ./gradlew runIde             # a sandbox IDE with the plugin installed
//
// Version notes: the platform plugin is 2.x and the IDE version below is what
// this was written against; bump `intellijIdeaCommunity(...)` and `sinceBuild`
// together for a newer IDE, and see README.md.

plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("jvm") version "2.0.21"
}

group = "dev.vela"
// Kept in step with <version> in src\main\resources\META-INF\plugin.xml, which is
// the source of truth the offline build reads.  Every plugin update bumps both,
// and writes the entry in CHANGELOG.md.
version = "0.1.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        // No bundled plugin is needed: no Java support, no LSP, nothing but the
        // platform.  A language plugin that needs a framework is a language
        // plugin that does too much.
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "Vela"
        ideaVersion {
            // Was 242 (the intended floor); 253 is the oldest platform that
            // actually exists on this machine, and it is what plugin.xml declares.
            sinceBuild = "253"
            untilBuild = provider { null }        // open-ended: no fake limit
        }
        description = """
            Vela language support for <code>.vel</code> and <code>.vela</code>
            files: syntax and semantic highlighting, diagnostics produced by the
            Vela compiler itself, completion, hover documentation, parameter
            info, go to declaration, rename and find usages, a structure view and
            an AST window, a formatter with indentation settings, folding,
            comments, brace matching, live templates, a colour scheme page, and a
            Run configuration.</code>

            The compiler (<code>selfhost/build/vm.exe</code>) writes
            <code>vela: kind: message</code> and the location on the next line;
            the annotations you see in the editor are those lines.  Nothing here
            re-implements the language.
        """.trimIndent()
    }
}

// Kotlin 2.x defaults to the newest JVM target; the platform is 21.
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
