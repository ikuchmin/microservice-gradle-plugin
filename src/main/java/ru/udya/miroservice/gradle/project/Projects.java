package ru.udya.miroservice.gradle.project;

import org.gradle.api.Project;

public class Projects {

    public static String getModuleNameByProject(Project project) {
        String moduleName;
        if (project.hasProperty("appModuleType") && project.property("appModuleType") != null) {
            moduleName = (String) project.property("appModuleType");
        } else {
            moduleName = project.getProjectDir().getName();
        }
        return moduleName;
    }

    public static boolean isFrontProject(Project project) {
        return project.getName().endsWith("-polymer-client") || "front".equals(getModuleNameByProject(project));
    }
}