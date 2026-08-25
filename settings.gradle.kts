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
    "backend:llm",
    "backend:analysis",
    "backend:api",
    "backend:app",
)
