pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "wtfuck"
include(":protocol")
include(":server")
include(":app")

// W0 de la version web: libsignal del navegador contra la de la JVM. Solo
// pruebas. Ver web/README.md.
//
// Solo si la carpeta esta. La imagen Docker del servidor copia protocol/ y
// server/ y nada de web/: incluir el modulo sin condicion rompia el build de la
// imagen en el VPS ("Configuring project ':interop-web' without an existing
// directory is not allowed").
if (file("web/interop-jvm").isDirectory) {
    include(":interop-web")
    project(":interop-web").projectDir = file("web/interop-jvm")
}
