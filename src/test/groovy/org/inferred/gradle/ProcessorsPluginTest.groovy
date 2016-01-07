package org.inferred.gradle

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.fail
import static com.google.common.collect.Iterables.getOnlyElement

import org.junit.Test

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency 
import org.gradle.api.file.FileCollection
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

  @Test
  public void addsProcessorAsProvidedInIdea() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'idea'
    project.pluginManager.apply 'org.inferred.processors'
    project.repositories {
      mavenCentral()
    }
    project.dependencies {
      processor 'org.inferred:freebuilder:1.0'
    }
    project.evaluate()

    assertDependencies project, 'COMPILE'
    assertDependencies project, 'RUNTIME'
    assertDependencies project, 'TEST'
    assertDependencies project, 'PROVIDED', 'org.inferred:freebuilder:1.0'
  }

  @Test
  public void skipsProcessorAlreadyInIdeaCompileScope() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'idea'
    project.pluginManager.apply 'org.inferred.processors'
    project.repositories {
      mavenCentral()
    }
    project.dependencies {
      compile 'org.inferred:freebuilder:1.0'
      processor 'org.inferred:freebuilder:1.0'
    }
    project.evaluate()

    assertDependencies project, 'COMPILE', 'org.inferred:freebuilder:1.0'
    assertDependencies project, 'RUNTIME'
    assertDependencies project, 'TEST'
    assertDependencies project, 'PROVIDED'
  }

  @Test
  public void movesProcessorInIdeaRuntimeScopeToCompileScope() {
    Project project = ProjectBuilder.builder().build()
    project.pluginManager.apply 'java'
    project.pluginManager.apply 'idea'
    project.pluginManager.apply 'org.inferred.processors'
    project.repositories {
      mavenCentral()
    }
    project.dependencies {
      runtime 'org.inferred:freebuilder:1.0'
      processor 'org.inferred:freebuilder:1.0'
    }
    project.evaluate()

    assertDependencies project, 'COMPILE', 'org.inferred:freebuilder:1.0'
    assertDependencies project, 'RUNTIME'
    assertDependencies project, 'TEST'
    assertDependencies project, 'PROVIDED'
  }

  private static void assertDependencies(Project project, String scope, String... expected) {
    Set<Dependency> actual = getIdeaDependencies(project, scope)
    Set<String> missing = new LinkedHashSet<>(Arrays.asList(expected))
    Set<Dependency> unexpected = new LinkedHashSet<>();
    for (Dependency dep : actual) {
      String asString = dep.group + ':' + dep.name + ':' + dep.version
      if (!missing.remove(asString)) {
        unexpected.add(dep);
      }
    }
    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      StringBuilder failureMessage = new StringBuilder(scope)
          .append(' dependencies ')
          .append(actual)
          .append(' failed to match expected ')
          .append(Arrays.asList(expected))
          .append(': ');
      String separator = '';
      if (!missing.isEmpty()) {
        failureMessage.append('missing ').append(missing)
        separator = ' and '
      }
      if (!unexpected.isEmpty()) {
        failureMessage.append(separator).append('unexpectedly contained ').append(unexpected)
      }
      fail(failureMessage.toString())
    }
  }

  private static Set<Dependency> getIdeaDependencies(Project project, String scope) {
    Set<Dependency> dependencies = new LinkedHashSet<>()
    // Add the defaults specified in the JavaDoc for IdeaModule#getScopes()
    switch (scope) {
      case 'COMPILE':
        dependencies.addAll(project.configurations.compile.dependencies)
        break;
      case 'RUNTIME':
        dependencies.addAll(project.configurations.runtime.dependencies)
        dependencies.removeAll(project.configurations.compile.dependencies)
      case 'TEST':
        dependencies.addAll(project.configurations.testRuntime.dependencies)
        dependencies.removeAll(project.configurations.runtime.dependencies)
      default:
        break;
    }
    for (Configuration added : project.idea.module.scopes[scope].plus) {
      dependencies.addAll(added.dependencies)
    }
    for (Configuration removed : project.idea.module.scopes[scope].minus) {
      dependencies.removeAll(removed.dependencies)
    }
    return dependencies
  }
}
