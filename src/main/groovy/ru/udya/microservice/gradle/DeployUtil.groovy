package ru.udya.microservice.gradle

import org.gradle.api.Project

class DeployUtil {

    static def microserviceJarNames(Project project) {
        if (project.hasProperty(MicroservicePlugin.MICROSERVICE_INHERITED_JAR_NAMES)) {
            return new ArrayList<>(project[MicroservicePlugin.MICROSERVICE_INHERITED_JAR_NAMES])
        }

        return []
    }
}
