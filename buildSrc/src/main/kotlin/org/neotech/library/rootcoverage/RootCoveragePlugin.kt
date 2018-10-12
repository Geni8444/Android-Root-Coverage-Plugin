package org.neotech.library.rootcoverage

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.SourceKind
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.tasks.JacocoReport

class RootCoveragePlugin: Plugin<Project> {

    private lateinit var rootProjectExtension: RootCoveragePluginExtension;

    override fun apply(project: Project) {
        if(project.rootProject !== project) {
            throw GradleException("The RootCoveragePlugin can not be applied to project '${project.name}' (${project.buildFile}) because it is not the root project!")
        }
        rootProjectExtension = project.extensions.create("rootCoverage", RootCoveragePluginExtension::class.java)

        if (project.plugins.withType(JacocoPlugin::class.java).isEmpty()) {
            project.logger.warn("Jacoco plugin not applied to project: '${project.name}'! RootCoveragePlugin automatically applied it but you should do this manually: ${project.buildFile}")
            project.plugins.apply(JacocoPlugin::class.java)
        }

        project.afterEvaluate { createCoverageTaskForRoot(it) }
    }

    private fun getFileFilter(): List<String> {
        return listOf(
                "**/AutoValue_*.*", // Filter to remove generated files from: https://github.com/google/auto
                //"**/*JavascriptBridge.class",

                // Android Databinding
                "**/*databinding",
                "**/*binders",
                "**/*layouts",
                "**/BR.class", // Filter to remove generated databinding files

                // Core Android generated class filters
                "**/R.class",
                "**/R$*.class",
                "**/Manifest*.*",
                "**/BuildConfig.class",
                "android/**/*.*",

                "**/*\$ViewBinder*.*",
                "**/*\$ViewInjector*.*",
                "**/Lambda$*.class",
                "**/Lambda.class",
                "**/*Lambda.class",
                "**/*Lambda*.class",
                "**/*\$InjectAdapter.class",
                "**/*\$ModuleAdapter.class",
                "**/*\$ViewInjector*.class") + rootProjectExtension.excludes
    }

    private fun getBuildVariantFor(project: Project): String {
        return rootProjectExtension.buildVariantOverrides[project.path] ?: rootProjectExtension.buildVariant
    }

    private fun createCoverageTaskForRoot(project: Project) {
        // Aggregates jacoco results from the app sub-project and bankingright sub-project and generates a report.
        // The report can be found at the root of the project in /build/reports/jacoco, so don't look in
        // /app/build/reports/jacoco you will only find the app sub-project report there.
        val task = project.tasks.create("rootCodeCoverageReport", JacocoReport::class.java)
        task.group = "reporting"
        task.description = "Generates a Jacoco report with combined results from all the subprojects."

        task.reports.html.isEnabled = true
        task.reports.xml.isEnabled = false
        task.reports.csv.isEnabled = false
        task.reports.html.destination = project.file("${project.buildDir}/reports/jacoco")

        project.subprojects.forEach {
            it.afterEvaluate { subProject ->
                createCoverageTaskForSubProject(subProject, task)
            }
        }
    }

    private fun createCoverageTaskForSubProject(subProject: Project, task: JacocoReport) {
        // Only Android Application and Android Library modules are supported for now.
        val extension = subProject.extensions.findByName("android")
        if(extension == null) {
            // TODO support java modules?
            subProject.logger.warn("Skipping code coverage for module '${subProject.name}': currently RootCoveragePlugin only supports Android Application or Android Library modules!");
            return
        } else if(extension is com.android.build.gradle.FeatureExtension) {
            // TODO support feature modules?
            subProject.logger.warn("Skipping code coverage for module '${subProject.name}': Currently RootCoveragePlugin does not yet support Android Feature Modules!")
            return
        }

        if (subProject.plugins.withType(JacocoPlugin::class.java).isEmpty()) {
            subProject.logger.warn("Jacoco plugin not applied to project: '${subProject.name}'! RootCoveragePlugin automatically applied it but you should do this manually: ${subProject.buildFile}")
            subProject.plugins.apply(JacocoPlugin::class.java)
        }

        // If the sub-project does not apply the jacoco plugin, skip that project.
        if (subProject.plugins.withType(JacocoPlugin::class.java).isNotEmpty()) {

            // Get the exact required build variant for the current subproject.
            val buildVariant = getBuildVariantFor(subProject)
            when(extension) {
                is LibraryExtension -> {
                    extension.libraryVariants.all { variant ->
                        if (variant.buildType.isTestCoverageEnabled && variant.name.capitalize() == buildVariant.capitalize()) {
                            val subTask = createTask(subProject, variant)
                            addSubTaskDependencyToRootTask(task, subTask)
                        }
                    }
                }
                is AppExtension -> {
                    extension.applicationVariants.all { variant ->
                        if (variant.buildType.isTestCoverageEnabled && variant.name.capitalize() == buildVariant.capitalize()) {
                            val subTask = createTask(subProject, variant)
                            addSubTaskDependencyToRootTask(task, subTask)
                        }
                    }
                }
            }
        }
    }

    private fun createTask(project: Project, variant: BaseVariant): JacocoReport {
        //println("Create code coverage report for variant ${project.name} ${variant.name}")

        val name = variant.name.capitalize()

        val task = project.tasks.create("codeCoverageReport$name", JacocoReport::class.java)
        task.group = null // null makes sure the group does not show in the gradle-view in Android Studio/Intellij
        task.description = "Generate unified Jacoco code codecoverage report"
        task.dependsOn("test${name}UnitTest", "connected${name}AndroidTest")

        task.reports.html.isEnabled = false
        task.reports.xml.isEnabled = false
        task.reports.csv.isEnabled = false
        //task.reports.html.destination = project.file("${project.buildDir}/reports/jacoco")

        // Collect the class files based on the Java Compiler output
        val javaClassTrees = variant.javaCompiler.outputs.files.map { file ->
            project.fileTree(file, excludes = getFileFilter()).excludeNonClassFiles()
        }
        // Hard coded alternative to get class files for Java.
        //val classesTree = project.fileTree(mapOf("dir" to "${project.buildDir}/intermediates/classes/${variant.dirName}", "excludes" to getFileFilter()))

        // TODO: No idea how to dynamically get the kotlin class files output folder... so for now this is hardcoded.
        val kotlinClassTree = project.fileTree("${project.buildDir}/tmp/kotlin-classes/${variant.dirName}", excludes =  getFileFilter()).excludeNonClassFiles()

        // getSourceFolders returns ConfigurableFileCollections, but we only need the base directory of each ConfigurableFileCollection.
        val sourceFiles = variant.getSourceFolders(SourceKind.JAVA).map { file -> file.dir }

        task.sourceDirectories = project.files(sourceFiles)
        task.classDirectories = project.files(javaClassTrees, kotlinClassTree)
        task.executionData = project.fileTree(project.buildDir, includes = listOf("jacoco/test${name}UnitTest.exec", "outputs/code-coverage/connected/*coverage.ec"))

        //println("Source files: ${task.classDirectories.files}")

        return task
    }

    private fun addSubTaskDependencyToRootTask(rootTask: JacocoReport, subModuleTask: JacocoReport) {

        // Make the root task depend on the sub-project code coverage task
        rootTask.dependsOn(subModuleTask)

        // Set or add the sub-task class directories to the root task
        if(rootTask.classDirectories == null) {
            rootTask.classDirectories = subModuleTask.classDirectories
        } else {
            rootTask.classDirectories += subModuleTask.classDirectories
        }

        // Set or add the sub-task source directories to the root task
        if(rootTask.sourceDirectories == null) {
            rootTask.sourceDirectories = subModuleTask.sourceDirectories
        } else {
            rootTask.sourceDirectories += subModuleTask.sourceDirectories
        }

        // Add the sub-task class directories to the root task
        rootTask.executionData(subModuleTask.executionData)
    }
}