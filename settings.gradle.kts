rootProject.name = "investor"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "backend:shared",
    "backend:ontology-core",
    "backend:market-data",
    "backend:knowledge",
    "backend:api",
    "backend:app",
)
