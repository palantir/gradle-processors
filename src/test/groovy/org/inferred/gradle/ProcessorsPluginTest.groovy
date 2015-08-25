package org.inferred.gradle

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull

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

    assertEquals 'generated_src', project.processors.sourceOutputDir
    assertEquals 'generated_testSrc', project.processors.testSourceOutputDir
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
}
