package org.inferred.gradle

import groovy.text.SimpleTemplateEngine
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.ide.api.XmlFileContentMerger
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.plugins.ide.idea.model.IdeaModule
import org.gradle.util.GUtil

class ProcessorsPlugin implements Plugin<Project> {

  void apply(Project project) {

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
    }

    project.plugins.withType(JavaPlugin, { plugin ->
      configureJavaCompilerTasks(project, ourProcessorConf, allProcessorsConf)
      def convention = project.convention.plugins['java'] as JavaPluginConvention
      convention.sourceSets.all { SourceSet sourceSet ->
        project.configurations[sourceSet.compileOnlyConfigurationName].extendsFrom ourProcessorConf
      }

      configureIdeaPlugin(project, allProcessorsConf)
      configureFindBugs(project)
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
  private void configureJavaCompilerTasks(
          Project project, Configuration ourProcessorsConf, Configuration allProcessorsConf) {
    // compat with gradle 4.6 annotationProcessor
    // The configuration is guaranteed to exist (for the 'main' sourceSet) if the java plugin was applied
    if (project.configurations.findByName('annotationProcessor') != null) {
      def convention = project.convention.plugins['java'] as JavaPluginConvention
      // Rely on gradle's annotationProcessor handling logic, and make sure it also picks up processors that were
      // added to the 'processor' configuration
      convention.sourceSets.all { SourceSet sourceSet ->
        def annotationProcessorConf = project.configurations[sourceSet.annotationProcessorConfigurationName]
        annotationProcessorConf.extendsFrom ourProcessorsConf
        allProcessorsConf.extendsFrom annotationProcessorConf
      }
    } else {
      project.tasks.withType(JavaCompile).all { JavaCompile compileTask ->
        compileTask.dependsOn project.task(GUtil.toLowerCamelCase('processorPath ' + compileTask.name), {
          doLast {
            String path = getProcessors(project).getAsPath()
            compileTask.options.compilerArgs += ["-processorpath", path]
          }
        })
      }
      project.tasks.withType(Javadoc).all { Javadoc javadocTask ->
        javadocTask.dependsOn project.task(GUtil.toLowerCamelCase('javadocProcessors ' + javadocTask.name), {
          doLast {
            Set<File> path = getProcessors(project).files
            javadocTask.options.classpath += path
          }
        })
      }
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
              project.file('generated/main/java')
            }
            processors.conventionMapping.testOutputDir = {
              project.file('generated/test/java')
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
                            testOutputDir: project.relativePath(project.eclipse.processors.testOutputDir).replace('\\', '\\\\'),
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
      extension.with {
        outputDir = 'generated_src'
        testOutputDir = 'generated_testSrc'
      }

      addGeneratedSourceFolder(project, { getIdeaSourceOutputDir(project) }, false)
      addGeneratedSourceFolder(project, { getIdeaSourceTestOutputDir(project) }, true)

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

  private static void configureFindBugs(Project project) {
    project.tasks.withType(FindBugs, { findBugsTask ->
      // Create a JAR containing all generated sources.
      // This trick relies on javac putting the generated .java files next to the .class files.
      def jarTask = project.tasks.create(
              name: findBugsTask.name + 'GeneratedClassesJar',
              type: org.gradle.api.tasks.bundling.Jar)
      jarTask.setDependsOn(findBugsTask.dependsOn)
      jarTask.doFirst {
        def generatedSources = findBugsTask.classes.filter {
          it.path.endsWith '.java'
        }
        Set<File> generatedClasses = findBugsTask.classes.filter {
          def javaFile = it.path.replaceFirst(/.class$/, '') + '.java'
          boolean isGenerated = generatedSources.contains(new File(javaFile))
          def outerFile = javaFile.replaceFirst(/\$\w+.java$/, '.java')
          while (outerFile != javaFile) {
            javaFile = outerFile
            isGenerated = isGenerated || generatedSources.contains(new File(javaFile))
            outerFile = javaFile.replaceFirst(/\$\w+.java$/, '.java')
          }
          return isGenerated
        }.files
        generatedClasses.each { jarTask.from(it) }
      }
      findBugsTask.dependsOn jarTask
      findBugsTask.doFirst {
        if (project.processors.suppressFindbugs) {
          // Exclude generated sources from FindBugs' traversal.
          findBugsTask.classes = findBugsTask.classes.filter { !jarTask.inputs.files.contains(it) }

          // Include the generated sources JAR on the FindBugs classpath
          def generatedClassesJar = jarTask.outputs.files.files.find { true }
          findBugsTask.classpath += project.files(generatedClassesJar)
        }
      }
    })
  }

  private static configureJacoco(Project project) {
    project.tasks.withType(jacocoReportClass).all({ jacocoReportTask ->
      // Use same trick as FindBugs above - assume that a class with a matching .java file is generated, and exclude
      jacocoReportTask.doFirst {
        def generatedSources = jacocoReportTask.classDirectories.asFileTree.filter {
          it.path.endsWith '.java'
        }

        //
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

  static FileCollection getProcessors(Project project) {
    ResolvedConfiguration config = project.configurations.processor.resolvedConfiguration
    return project.files(config.getFiles({ d -> true } as Spec<Object>))
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
    profileForModule.replaceNode {
      profile(name: project.path, enabled: 'true') {
        sourceOutputDir(name: getIdeaSourceOutputDir(project))
        sourceTestOutputDir(name: getIdeaSourceTestOutputDir(project))
        outputRelativeToContentRoot(value: 'true')
        processorPath(useClasspath: 'false') {
          processorsConfiguration.forEach {
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
  boolean suppressFindbugs = true
}

class EclipseProcessorsExtension {
  Object outputDir
  Object testOutputDir
}

class IdeaProcessorsExtension {
  Object outputDir
  Object testOutputDir
}
