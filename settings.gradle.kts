rootProject.name = "investor"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "backend:shared",
    "backend:ontology-core",
    "backend:api",
    "backend:app",
)
