package ru.udya.microservice.gradle

import groovy.util.slurpersupport.GPathResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.component.SoftwareComponentFactory
import ru.udya.miroservice.gradle.project.Projects

import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import java.util.jar.JarFile
import java.util.jar.Manifest

import static org.apache.commons.io.IOUtils.closeQuietly

class MicroservicePlugin implements Plugin<Project> {

    public static final String MICROSERVICE_INHERITED_JAR_NAMES = 'inheritedMicroserviceDeployJarNames'

    public static final String APP_COMPONENT_ID_MANIFEST_ATTRIBUTE = 'App-Component-Id'
    public static final String APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE = 'App-Component-Version'
    public static final String APP_DESCRIPTION_FILE = 'app-component.xml'

    private final SoftwareComponentFactory softwareComponentFactory

    @Inject
    MicroservicePlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory
    }

    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')

        project.logger.info("[MicroservicePlugin] applying to project $project.name")

        if (project == project.rootProject) {
            applyToRootProject(project)
        }

        project.java {
            registerFeature('microserviceDependencies') {
                usingSourceSet(project.sourceSets.main)
            }
        }

        project.configurations {
            microserviceCompile
            microserviceOnlyInDeps

            compile.extendsFrom(microserviceCompile)
            compile.extendsFrom(microserviceOnlyInDeps)
            runtime.extendsFrom(microserviceDependenciesImplementation)

        }
//        if (project != project.rootProject) {
//
//            project.configurations {
//                microservice
//            }
//
//            // create an adhoc component
//            def adhocComponent = softwareComponentFactory.adhoc("myAdhocComponent")
//            // add it to the list of components that this project declares
//            project.components.add(adhocComponent)
//            // and register a variant for publication
//
//            project.logger.info("[MicroservicePlugin] applying to project ${project.components.collect {it.name}}")
//
////            project.components.java.withVariantsFromConfiguration(project.configurations.runtimeElements) {
////                skip()
////            }
//
//            project.logger.info("[MicroservicePlugin] applying to project ${project.configurations.dump()}")
//
//            adhocComponent.addVariantsFromConfiguration(project.configurations.microservice) {
//                it.mapToMavenScope("runtime")
//            }
//        }

        project.afterEvaluate { Project p ->
            addDependenciesFromMicroservices(p)
        }
    }

    private void applyToRootProject(Project project) {
        project.configurations {
            microservice
        }
    }

    private void addDependenciesFromMicroservices(Project project) {
        def moduleName = Projects.getModuleNameByProject(project)

        project.logger.info("[MicroservicePlugin] Setting up dependencies for module $moduleName")

        def microserviceConf = project.rootProject.configurations.microservice
        if (microserviceConf.dependencies.size() > 0) {
            addDependenciesFromMicroservicesConfiguration(project, moduleName)
        } else {
            addDependenciesFromMicroservicesClassPath(project, moduleName)
        }
    }

    private void addDependenciesFromMicroservicesClassPath(Project project, String moduleName) {
        project.logger.info("[MicroservicePlugin] Import microservices to ${project.name} from classpath")

        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (manifests.hasMoreElements()) {
            def manifest = new Manifest(manifests.nextElement().openStream())

            def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
            def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)

            if (compId && compVersion) {
                def compDescrPath = compId.replace('.', '/') + '/' + APP_DESCRIPTION_FILE
                def compGroup = compId

                def url = MicroservicePlugin.class.getResource(compDescrPath)
                if (url) {
                    project.logger.info("[MicroservicePlugin] Found microservice info in $url")
                    def xml = new XmlSlurper().parseText(url.openStream().getText('UTF-8'))

                    applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                }
            }
        }

        if (!jarNames.isEmpty()) {
            project.logger.info("[MicroservicePlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext[MICROSERVICE_INHERITED_JAR_NAMES] = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void addDependenciesFromMicroservicesConfiguration(Project project, String moduleName) {
        project.logger.info("[MicroservicePlugin] Import microservices to ${project.name} from microservice configuration")

        def addedArtifacts = new HashSet<ResolvedArtifact>()
        def jarNames = new HashSet<String>()
        def skippedDeps = new ArrayList<SkippedDep>()

        def appComponentConf = project.rootProject.configurations.microservice
        def resolvedConfiguration = appComponentConf.resolvedConfiguration
        def dependencies = resolvedConfiguration.firstLevelModuleDependencies

        project.ext.resolvedAppComponents = []

        walkJarDependencies(dependencies, addedArtifacts, { artifact ->
            def jarFile = new JarFile(artifact.file)
            try {
                def manifest = jarFile.manifest
                if (manifest == null) {
                    return
                }

                def compId = manifest.mainAttributes.getValue(APP_COMPONENT_ID_MANIFEST_ATTRIBUTE)
                def compVersion = manifest.mainAttributes.getValue(APP_COMPONENT_VERSION_MANIFEST_ATTRIBUTE)
                if (compId == null || compVersion == null) {
                    return
                }

                project.logger.info("[MicroservicePlugin] Inspect microservice dependency ${artifact.name}")

                def compDescriptorPath = compId.replace('.', '/') + '/' + APP_DESCRIPTION_FILE
                def compGroup = compId

                def descriptorEntry = jarFile.getEntry(compDescriptorPath)
                if (descriptorEntry != null) {
                    project.ext.resolvedAppComponents.add(compId + ':' + compVersion)

                    def descriptorInputStream = jarFile.getInputStream(descriptorEntry)
                    descriptorInputStream.withCloseable {
                        project.logger.info("[MicroservicePlugin] Found microservice info in ${artifact.file.absolutePath}")
                        def xml = new XmlSlurper().parseText(descriptorInputStream.getText(StandardCharsets.UTF_8.name()))

                        applyAppComponentXml(xml, moduleName, compGroup, compVersion, project, skippedDeps, compId, jarNames)
                    }
                }
            } finally {
                closeQuietly(jarFile)
            }
        })

        if (!jarNames.isEmpty()) {
            project.logger.info("[MicroservicePlugin] Inherited app JAR names for deploy task: $jarNames")
            project.ext[MICROSERVICE_INHERITED_JAR_NAMES] = jarNames
        }

        if (!skippedDeps.isEmpty()) {
            skippedDeps.sort()
            def last = skippedDeps.last()
            addDependencyToConfiguration(project, last.dep, last.conf)
        }
    }

    private void walkJarDependencies(Set<ResolvedDependency> dependencies,
                                     Set<ResolvedArtifact> passedArtifacts,
                                     Consumer<ResolvedArtifact> artifactAction) {
        for (dependency in dependencies) {
            walkJarDependencies(dependency.children, passedArtifacts, artifactAction)

            for (artifact in dependency.moduleArtifacts) {
                if (passedArtifacts.contains(artifact)) {
                    continue
                }

                passedArtifacts.add(artifact)

                if (artifact.file != null && artifact.file.name.endsWith('.jar')) {
                    artifactAction.accept(artifact)
                }
            }
        }
    }

    private void applyAppComponentXml(GPathResult xml, String moduleName, String compGroup, String compVersion,
                                      Project project, List<SkippedDep> skippedDeps, String compId, Set<String> jarNames) {

//        if (moduleName == "global") {
//            GPathResult module = (GPathResult) xml.module.find { it.@name == moduleName }
        GPathResult module = (GPathResult) xml.module.find { it.@name == "global" }

        if (module.size() > 0) {
                module.artifact.each { art ->
                    if (Boolean.valueOf(art.@library.toString()))
                        return

                    String dep = "$compGroup:${art.@name}:$compVersion"
                    if (art.@classifier != "" || art.@ext != "") {
                        dep += ':'
                        if (art.@classifier != "") {
                            dep += art.@classifier
                        }
                        if (art.@ext != "") {
                            dep += "@${art.@ext}"
                        }
                    }
                    if (art.@skipIfExists != "") {
                        if (!project.rootProject.allprojects.find { art.@skipIfExists == Projects.getModuleNameByProject(it) }) {
                            skippedDeps.add(new SkippedDep(new Microservice(compId, xml), dep, art.@configuration.text()))
                        }
                    } else {
                        addDependencyToConfiguration(project, dep, art.@configuration.text())
                    }
                }

                addJarNamesFromModule(jarNames, xml, module)
            }
//        }


        // Adding appJars from modules that work in all blocks. For example, not all components have
        // portal module, so they don't export appJars for a portal module in the project. But
        // project's global module depends from components' global modules and hence they should be added
        // to appJars.
        def globalModules = xml.module.findAll { it.@blocks.text().contains('*') }
        globalModules.each { GPathResult child ->
            addJarNamesFromModule(jarNames, xml, child)
        }
    }

    private void addDependencyToConfiguration(Project project, String dependency, String conf) {
        project.logger.info("[MicroservicePlugin] Adding dependency '$dependency' to configuration '$conf'")
        switch (conf) {
            case 'dbscripts':
            case 'webcontent':
            case 'themes':
                project.logger.info("[MicroservicePlugin] Dependency '$dependency' skipped ")

                break
            case '':
                project.dependencies {
                    microserviceDependenciesImplementation(dependency)
                }

                project.logger.info("[MicroservicePlugin] Dependency '$dependency' added to compile ")
                break

            default:
                project.dependencies.add(conf, dependency)

                project.logger.info("[MicroservicePlugin] Dependency '$dependency' added  to configuration '$conf'")
                break
        }
    }

    private void addJarNamesFromModule(Set jarNames, GPathResult xml, GPathResult module) {
        module.artifact.each { art ->
            if (art.@appJar == "true") {
                jarNames.add(art.@name.text())
            }
            module.@dependsOn.text().tokenize(' ,').each { depName ->
                GPathResult depModule = (GPathResult) xml.module.find { it.@name == depName }
                addJarNamesFromModule(jarNames, xml, depModule)
            }
        }
    }

    private static class Microservice {
        String id
        List<String> dependencies = []

        Microservice(String id, GPathResult xml) {
            this.id = id
            if (xml.@dependsOn) {
                dependencies = xml.@dependsOn.text().tokenize(' ,')
            }
        }

        boolean dependsOn(Microservice other) {
            return dependencies.contains(other.id)
        }
    }

    private static class SkippedDep implements Comparable<SkippedDep> {
        Microservice appComponent
        String dep
        String conf

        SkippedDep(Microservice appComponent, String dep, String conf) {
            this.appComponent = appComponent
            this.dep = dep
            this.conf = conf
        }

        @Override
        int compareTo(SkippedDep other) {
            if (this.appComponent.dependsOn(other.appComponent))
                return 1
            if (other.appComponent.dependsOn(this.appComponent))
                return -1
            return 0
        }
    }
}
