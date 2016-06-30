package org.inferred.gradle

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

import org.junit.Test

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.testfixtures.ProjectBuilder

class ProcessorsPluginTest {

  @Test
  public void addsProcessorDependenciesToJavaClasspath() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'org.inferred.processors'
    project.pluginManager.apply 'java'
    project.dependencies {
      processor 'org.inferred:freebuilder:1.0'
    }
  }

  @Test
  public void addsSourceDirectoryConfiguration() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'org.inferred.processors'
    project.pluginManager.apply 'idea'
    project.pluginManager.apply 'java'

    assertEquals 'generated_src', project.idea.processors.outputDir
    assertEquals 'generated_testSrc', project.idea.processors.testOutputDir
  }

  @Test
  public void addsEclipseConfigurationTasks_processorsFirst() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'org.inferred.processors'
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'eclipse'

    assertNotNull project.tasks.eclipseAptPrefs
    assertNotNull project.tasks.eclipseFactoryPath
  }

  @Test
  public void addsEclipseConfigurationTasks_processorsLast() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'eclipse'
    project.pluginManager.apply 'org.inferred.processors'

    assertNotNull project.tasks.eclipseAptPrefs
    assertNotNull project.tasks.eclipseFactoryPath
  }

  @Test
  public void configuresIdeaGeneratedSourcesDirectories() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'org.inferred.processors'
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'idea'

    assertTrue project.idea.module.sourceDirs.contains(project.file('generated_src'))
    assertTrue project.idea.module.generatedSourceDirs.contains(project.file('generated_src'))
    assertTrue project.idea.module.testSourceDirs.contains(project.file('generated_testSrc'))
    assertTrue project.idea.module.generatedSourceDirs.contains(project.file('generated_testSrc'))
  }

  @Test
  public void addsEclipseConfigurationTasks_eclipseFirst() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'org.inferred.processors'
    project.pluginManager.apply 'eclipse'
    project.pluginManager.apply 'java'

    assertNotNull project.tasks.eclipseAptPrefs
    assertNotNull project.tasks.eclipseFactoryPath
  }
}
