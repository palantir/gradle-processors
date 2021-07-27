package org.inferred.gradle

import groovy.text.SimpleTemplateEngine
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.plugins.ide.api.XmlFileContentMerger
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.plugins.ide.idea.model.IdeaModule
import org.gradle.util.GradleVersion

class ProcessorsPlugin implements Plugin<Project> {

  void apply(Project project) {
    if (GradleVersion.current() < GradleVersion.version("6.9")) {
      throw new GradleException("This plugin requires Gradle 6.9+")
    }

    project.extensions.create('processors', ProcessorsExtension)

    def ourProcessorConf = project.configurations.create('processor') {
      visible = false
      description = "The only configuration where processors should be added by the user. These are also added to " +
              "the 'compileOnly' configuration of every sourceSet."
    }
    def allProcessorsConf = project.configurations.create('allProcessors') {
      extendsFrom ourProcessorConf
      visible = false
      description = "The sink configuration that collects all processors, whether defined in a SourceSet's custom " +
              "annotationProcessor configuration (gradle 4.6+) or directly in the processor configuration exposed " +
              "by this plugin."
      canBeConsumed = false
      // If using a processor defined in the same build, it's useful to point to its classes directory rather than
      // to require rebuilding the jar every time. This ensures that when resolving, we get the "classes" variant out
      // of such projects, if it is defined.
      attributes {
        attribute Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.JAVA_API)
      }
    }

    project.plugins.withType(JavaPlugin, { plugin ->
      configureJavaCompilerTasks(project, ourProcessorConf, allProcessorsConf)
      def convention = project.convention.plugins['java'] as JavaPluginConvention
      convention.sourceSets.all { SourceSet sourceSet ->
        project.configurations[sourceSet.compileOnlyConfigurationName].extendsFrom ourProcessorConf
      }

      configureIdeaPlugin(project, allProcessorsConf)
      configureJacoco(project)
    })
    // Eclipse is a special snowflake because of nested on-plugin-application initializers
    configureEclipsePlugin(project, allProcessorsConf)
  }


  /**
   * Configures javac, groovy, etc., such that processors from {@code ourProcessorsConf} are picked up, and all
   * processors coming from there as well as Gradle 4.6+ {@link org.gradle.api.tasks.SourceSet#getAnnotationProcessorConfigurationName()
   * source set annotation processor configurations} will be included in {@code allProcessorsConf}.
   */
  private static void configureJavaCompilerTasks(
          Project project, Configuration ourProcessorsConf, Configuration allProcessorsConf) {
    def convention = project.convention.plugins['java'] as JavaPluginConvention
    // Rely on gradle's annotationProcessor handling logic, and make sure it also picks up processors that were
    // added to the 'processor' configuration
    convention.sourceSets.all { SourceSet sourceSet ->
      def annotationProcessorConf = project.configurations[sourceSet.annotationProcessorConfigurationName]
      annotationProcessorConf.extendsFrom ourProcessorsConf
      allProcessorsConf.extendsFrom annotationProcessorConf
    }
  }

  private void configureEclipsePlugin(Project project, Configuration allProcessorConf) {
    project.plugins.withType(EclipsePlugin) {
      // JavaBasePlugin & JavaPlugin again are necessary as EclipsePlugin wires up classpath configuration after
      // JavaPlugin and we need to be applied after that.
      project.plugins.withType(JavaBasePlugin) {
        project.plugins.withType(JavaPlugin) {
          project.eclipse {
            extensions.create('processors', EclipseProcessorsExtension)
            processors.conventionMapping.outputDir = {
              project.file('generated/java')
            }

            // If this is empty, then it means EclipsePlugin didn't initialize it yet
            if (classpath.plusConfigurations.empty) {
              project.logger.error(
                      "EclipseClasspath::plusConfigurations should not be empty, this indicates that EclipsePlugin "
                              + "didn't initialize it by the time we tried to mutate it")
            }
            classpath.plusConfigurations += [allProcessorConf]
            if (jdt != null) {
              jdt.file.withProperties {
                it['org.eclipse.jdt.core.compiler.processAnnotations'] = 'enabled'
              }
            }
          }

          templateTask(
                  project,
                  'eclipseAptPrefs',
                  'org/inferred/gradle/apt-prefs.template',
                  '.settings/org.eclipse.jdt.apt.core.prefs',
                  {
                    [
                            outputDir: project.relativePath(project.eclipse.processors.outputDir).replace('\\', '\\\\'),
                            deps     : allProcessorConf
                    ]
                  }
          )
          project.tasks.eclipseAptPrefs.inputs.files allProcessorConf
          project.tasks.eclipse.dependsOn project.tasks.eclipseAptPrefs
          project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseAptPrefs

          templateTask(
                  project,
                  'eclipseFactoryPath',
                  'org/inferred/gradle/factorypath.template',
                  '.factorypath',
                  { [deps: allProcessorConf] }
          )
          project.tasks.eclipseFactoryPath.inputs.files allProcessorConf
          project.tasks.eclipse.dependsOn project.tasks.eclipseFactoryPath
          project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseFactoryPath
        }
      }
    }
  }

  private static void configureIdeaPlugin(Project project, Configuration allProcessorConf) {
    project.plugins.withType(IdeaPlugin, { plugin ->
      IdeaModel idea = project.extensions.getByType(IdeaModel)
      def extension = (idea as ExtensionAware).extensions.create('processors', IdeaProcessorsExtension)

      // Ensure we're not importing from intellij before configuring these, otherwise we will conflict with Intellij's
      // own way of handling annotation processor output directories.
      if (!Boolean.getBoolean("idea.active")) {
        extension.with {
          outputDir = 'generated_src'
          testOutputDir = 'generated_testSrc'
        }

        addGeneratedSourceFolder(project, { getIdeaSourceOutputDir(project) }, false)
        addGeneratedSourceFolder(project, { getIdeaSourceTestOutputDir(project) }, true)
      }

      // Root project configuration
      def rootModel = project.rootProject.extensions.findByType(IdeaModel)
      if (rootModel != null && rootModel.project != null) {
        rootModel.project.ipr { XmlFileContentMerger merger ->
          merger.withXml { XmlProvider it ->
            // This file is only generated in the root project, but the user may not have applied
            //   the gradle-processors plugin to the root project.
            // In either case, we add a distinct profile for each project.
            setupIdeaAnnotationProcessing(project, idea.module, it.asNode(), allProcessorConf)
          }
        }
      }
    })
  }

  private static configureJacoco(Project project) {
    // JacocoReport.classDirectories was deprecated in gradle 5 and breaks with the current code
    if (GradleVersion.current() >= GradleVersion.version("5.0")) {
      return
    }

    project.tasks.withType(jacocoReportClass).all({ jacocoReportTask ->
      // Assume that a class with a matching .java file is generated, and exclude
      jacocoReportTask.doFirst {
        def generatedSources = jacocoReportTask.classDirectories.asFileTree.filter {
          it.path.endsWith '.java'
        }
        jacocoReportTask.classDirectories = jacocoReportTask.classDirectories.asFileTree.filter {
          if (generatedSources.contains(it)) return false

          def javaFile = it.path.replaceFirst(/.class$/, '') + '.java'
          boolean isGenerated = generatedSources.contains(new File(javaFile))
          def outerFile = javaFile.replaceFirst(/\$\w+.java$/, '.java')
          while (outerFile != javaFile) {
            javaFile = outerFile
            isGenerated = isGenerated || generatedSources.contains(new File(javaFile))
            outerFile = javaFile.replaceFirst(/\$\w+.java$/, '.java')
          }
          return !isGenerated
        }
      }
    })
  }

  static void templateTask(project, taskName, templateFilename, outputFilename, binding) {
    def outputFile = new File(project.projectDir, outputFilename)
    def cleanTaskName = "clean" + taskName.substring(0, 1).toUpperCase() + taskName.substring(1)
    project.task(taskName, {
      outputs.file outputFile
      doLast {
        outputFile.parentFile.mkdirs()
        def stream = getClass().classLoader.getResourceAsStream templateFilename
        try {
          def reader = new InputStreamReader(stream, "UTF-8")
          def template = new SimpleTemplateEngine().createTemplate(reader)
          def writable = template.make binding()
          def writer = new FileWriter(outputFile)
          try {
            writable.writeTo(writer)
          } finally {
            writer.close()
          }
        } finally {
          stream.close()
        }
      }
    })

    project.task(cleanTaskName, type: Delete) {
      delete outputFile
    }
  }

  static void setupIdeaAnnotationProcessing(
      Project project, IdeaModule ideaModule, Node projectConfiguration, Configuration processorsConfiguration) {
    Node compilerConfiguration = projectConfiguration.component
            .find { it.@name == 'CompilerConfiguration' }

    if (compilerConfiguration == null) {
      throw new GradleException("Unable to find CompilerConfiguration element")
    }

    if (compilerConfiguration.annotationProcessing.isEmpty()) {
      new Node(compilerConfiguration, "annotationProcessing")
    }

    project.logger.info("Configuring annotationProcessing profile for ${ideaModule.name}")

    // Add a profile specifically for this module, or re-use an existing profile for that module
    Node processingNode = (compilerConfiguration.annotationProcessing as NodeList).first()
    def profileForModule = processingNode.profile.find { it.module.any { it.@name == ideaModule.name } }
        ?: processingNode.appendNode('profile')

    def processorFiles = processorsConfiguration.incoming.artifacts.artifacts.collect { artifact ->
      def id = artifact.id.componentIdentifier
      if (id instanceof ProjectComponentIdentifier && artifact.variant.attributes.contains(Usage.USAGE_ATTRIBUTE)) {
        Project dependencyProject = project.rootProject.project(id.projectPath)
        IdeaModel idea = dependencyProject.extensions.getByType(IdeaModel)
        def dependencyModule = idea.module
        def projectOutputDir = projectConfiguration.component
                .find { it.@name == 'ProjectRootManager' }
                .output
                .@url[0]
                .replaceAll('^\\Qfile://$PROJECT_DIR$/', '')
        def outputDir = dependencyModule.outputDir
                ?: project.rootProject.file("${projectOutputDir}/production/${dependencyModule.name}")
        return outputDir
      } else {
        return artifact.file
      }
    }
    profileForModule.replaceNode {
      profile(name: project.path, enabled: 'true') {
        sourceOutputDir(name: getIdeaSourceOutputDir(project))
        sourceTestOutputDir(name: getIdeaSourceTestOutputDir(project))
        outputRelativeToContentRoot(value: 'true')
        processorPath(useClasspath: 'false') {
          processorFiles.forEach {
            entry(name: it.absolutePath)
          }
        }
        module(name: ideaModule.name)
      }
    }
  }

  private static void addGeneratedSourceFolder(
          Project project,
          Object outputDir,
          boolean isTest) {
    File generatedSourceOutputDir = project.file(outputDir)

    // add generated directory as source directory
    project.idea.module.generatedSourceDirs += project.file(outputDir)
    if (!isTest) {
      project.idea.module.sourceDirs += project.file(outputDir)
    } else {
      project.idea.module.testSourceDirs += project.file(outputDir)
    }

    // if generated source directory doesn't already exist, Gradle IDEA plugin will not add it as a source folder,
    // so manually add as generated source folder to the .iml
    project.idea.module.iml {
      withXml {
        def path = project.relativePath(outputDir)
        def dirUrl = "file://\$MODULE_DIR\$/${path}"
        def content = node.component.content[0]
        if (content.find { it.url == dirUrl } == null) {
          content.appendNode(
              'sourceFolder', [
                  url          : dirUrl,
                  isTestSource : isTest,
                  generated    : "true"
              ]
          )
        }
      }
    }
  }

  private static String getIdeaSourceOutputDir(Project project) {
    if (project.rootProject.hasProperty('idea') && project.rootProject.idea.hasProperty('processors')) {
      return project.rootProject.idea.processors.outputDir
    } else {
      return 'generated_src'
    }
  }

  private static String getIdeaSourceTestOutputDir(Project project) {
    if (project.rootProject.hasProperty('idea') && project.rootProject.idea.hasProperty('processors')) {
      return project.rootProject.idea.processors.testOutputDir
    } else {
      return 'generated_testSrc'
    }
  }

  private static Class getJacocoReportClass() {
    try {
      // Only exists in Gradle 1.6+
      return Class.forName('org.gradle.testing.jacoco.tasks.JacocoReport')
    } catch (ClassNotFoundException ex) {
      // Couldn't find JacocoReport, skip JaCoCo integration
      return Void.class
    }
  }

}

class ProcessorsExtension {
}

class EclipseProcessorsExtension {
  Object outputDir
}

class IdeaProcessorsExtension {
  Object outputDir
  Object testOutputDir
}
