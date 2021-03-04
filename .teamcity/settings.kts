import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.Swabra
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.swabra
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.*
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs
import jetbrains.buildServer.configs.kotlin.v2019_2.vcs.GitVcsRoot
/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2020.1"

project {
    vcsRoot(MicroserviceGradlePluginVcs)

    buildType(General)
    buildType(Build)

    buildTypesOrder = arrayListOf(General, Build)
}

object Build : BuildType({
    templates(AbsoluteId("BespokeUnit_Hse_BackendSetBuildNumberBasedOnGradleVersion"))

    name = "Build & Publish to Dev Env"

    type = Type.REGULAR

    vcs {
        root(MicroserviceGradlePluginVcs)
    }

    val artifactSummary = "build/distributions/published_artifacts.json"

    publishArtifacts = PublishMode.SUCCESSFUL
    artifactRules = artifactSummary

    val dockerImagesLoaderPluginDockerImage = "%dockerDevRepository%/teamcity/docker-images-loader:latest"

    val libericaOpenJdkDockerImage = "bellsoft/liberica-openjdk-alpine:11"
    val mavenPluginDockerImage = "mavenplugin:latest" // needed for publish template
    val dependencyVerifierPluginDockerImage = "teamcity/dependency-verifier:1.0"
    val artifactsManagementPluginDockerImage = "teamcity/artifacts-management:1.0"
    val dockerImages = arrayListOf(libericaOpenJdkDockerImage,
            mavenPluginDockerImage, dependencyVerifierPluginDockerImage,
            artifactsManagementPluginDockerImage)

    params {
        param("repoUser", "%hseAutomationUser%")
        param("repoPass", "%hseAutomationPassword%")
        param("dockerRegistry", "%dockerDevRepository%")
        param("tcRestAccessToken", "%hseAutomationTCRestToken%")
        param("publicationRepoUser", "%hseAutomationUser%")
        param("publicationRepoPassword", "%hseAutomationPassword%")
        param("publicationRepoUrl", "%mavenThirdPartyRepository%")
        param("gradleArtifactsBasePath", "build/distributions")
    }

    steps {
        exec {
            id = "PRELOAD_DOCKER_IMAGES"
            name = "Preload Docker Images"
            path = "/usr/local/bin/docker-entrypoint.sh"
            arguments = "load"
            dockerImage = dockerImagesLoaderPluginDockerImage
            dockerRunParameters = """
                    --env "PLUGIN_TC_REGISTRY_URL=%dockerDevRepository%"
                    --env "PLUGIN_TC_DOCKER_IMAGES=${dockerImages.joinToString(separator = " ")}"
                    -v /var/run/docker.sock:/var/run/docker.sock
                    -v %env.HOME%/.docker/config.json:/root/.docker/config.json
                    """.trimIndent()
        }

        gradle {
            id = "BUILD"
            name = "Build Artifacts"
            tasks = "clean buildArtifactsForAllModule"
            gradleParams = """
                -PrepoUser=%repoUser%
                -PrepoPass=%repoPass%
                -PgitBranch=%teamcity.build.branch%
                """.trimIndent()
            useGradleWrapper = true
            dockerImage = libericaOpenJdkDockerImage
            dockerRunParameters = "-v \"%teamcity.build.checkoutDir%/local_m2:/root/.m2\""
        }

        exec {
            id = "PUBLISH_ARTIFACT_TO_DEV_BY_BUILD_NUMBER"
            name = "Publish artifacts to Dev by Build number"
            path = "/usr/local/bin/docker-entrypoint.sh"
            arguments = "maven-publish"
            dockerImage = artifactsManagementPluginDockerImage
            dockerRunParameters = """
                --env "PLUGIN_TC_REPOSITORY_URL=${DslContext.getParameter("DevRepositoryUrl")}" 
                --env "PLUGIN_TC_USERNAME=${DslContext.getParameter("DevRepositoryUsername")}"
                --env "PLUGIN_TC_PASSWORD=${DslContext.getParameter("DevRepositoryPassword")}"
                --env "PLUGIN_TC_OUTPUT=$artifactSummary"
                --env "PLUGIN_TC_WITH_VERSION=%build.number%-SNAPSHOT"
                --env "PLUGIN_TC_LOG_LEVEL=DEBUG"
                --env "PLUGIN_TC_POM_FILE_PATHS=build/distributions/microservice-gradle-plugin/pom-default.xml"
            """.trimIndent() // -SNAPSHOT is inplace hack
        }

        exec {
            id = "PUBLISH_ARTIFACT_TO_THIRD_PARTY"
            name = "Publish artifacts to Third-Party"

            conditions {
                equals("teamcity.build.branch.is_default", "true")
            }

            path = "/usr/local/bin/docker-entrypoint.sh"
            arguments = "maven-publish"
            dockerImage = artifactsManagementPluginDockerImage
            dockerRunParameters = """
                --env "PLUGIN_TC_REPOSITORY_URL=${DslContext.getParameter("DevRepositoryUrl")}" 
                --env "PLUGIN_TC_USERNAME=${DslContext.getParameter("DevRepositoryUsername")}"
                --env "PLUGIN_TC_PASSWORD=${DslContext.getParameter("DevRepositoryPassword")}"
                --env "PLUGIN_TC_LOG_LEVEL=DEBUG"
                --env "PLUGIN_TC_POM_FILE_PATHS=build/distributions/microservice-gradle-plugin/pom-default.xml"
            """.trimIndent()
        }

        stepsOrder = arrayListOf("PRELOAD_DOCKER_IMAGES", "RUNNER_20", "BUILD",
                "PUBLISH_ARTIFACT_TO_DEV_BY_BUILD_NUMBER", "PUBLISH_ARTIFACT_TO_THIRD_PARTY")
    }

    triggers {
        vcs {}
    }

    features {
        dockerSupport {
            id = "DockerSupport"
            cleanupPushedImages = true
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_8"
            }
        }
    }
})

object General : BuildType({

    name = "General"

    type = Type.COMPOSITE

    vcs { showDependenciesChanges = true }

    dependencies {
        dependency(Build) {
            snapshot { }
            artifacts {
                cleanDestination = true
                artifactRules = "** => dependencies"
            }
        }
    }
})

object MicroserviceGradlePluginVcs : GitVcsRoot({
    name = "Microservice Gradle Plugin"
    url = "https://github.com/ikuchmin/microservice-gradle-plugin.git"
    branchSpec = "+:refs/heads/*"
    authMethod = uploadedKey {
        uploadedKey = "hse-automation"
    }
})