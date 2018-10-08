package org.inferred.gradle

import groovy.text.SimpleTemplateEngine
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin
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
        // Preserve previously agreed behaviour where just adding something to `annotationProcessor` would add it to the
        // compile classpath as well, to make testAnnotationProcessor pass
        project.configurations[sourceSet.compileOnlyConfigurationName].extendsFrom(
                project.configurations.annotationProcessor)
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

  private void configureIdeaPlugin(Project project, Configuration allProcessorConf) {
    project.plugins.withType(IdeaPlugin, { plugin ->
      if (project == project.rootProject) {
        // Generated source directories can only be specified per-workspace in IntelliJ.
        // As such, it only makes sense to allow the user to configure them on the root project.
        // If the gradle-processors plugin is not applied to the root project, we just use
        //   the default values.
        project.idea.extensions.create('processors', IdeaProcessorsExtension)
        project.idea.processors {
          outputDir = 'generated_src'
          testOutputDir = 'generated_testSrc'
        }
      }

      if (project.idea.module.scopes.PROVIDED != null) {
        project.idea.module.scopes.PROVIDED.plus += [allProcessorConf]
      }

      addGeneratedSourceFolder(project, { getIdeaSourceOutputDir(project) }, false)
      addGeneratedSourceFolder(project, { getIdeaSourceTestOutputDir(project) }, true)

      // Root project configuration
      if (project.rootProject.hasProperty('idea') && project.rootProject.idea.project != null) {
        project.rootProject.idea.project.ipr {
          withXml {
            // This file is only generated in the root project, but the user may not have applied
            //   the gradle-processors plugin to the root project. Instead, we update it from
            //   every project idempotently.
            updateIdeaCompilerConfiguration(project.rootProject, node, false)
          }
        }
      }
    })

    project.afterEvaluate {
      // If the project uses .idea directory structure, and we are running within IntelliJ, update
      //   compiler.xml directly
      // This file is only generated in the root project, but the user may not have applied
      //   the gradle-processors plugin to the root project. Instead, we update it from every
      //   project idempotently.
      def inIntelliJ = System.properties.'idea.active' as boolean
      File ideaCompilerXml = project.rootProject.file('.idea/compiler.xml')
      if (inIntelliJ && ideaCompilerXml.isFile()) {
        Node parsedProjectXml = (new XmlParser()).parse(ideaCompilerXml)

        updateIdeaCompilerConfiguration(project.rootProject, parsedProjectXml, determineIfSeparateModulePerSourceSet(project))
        ideaCompilerXml.withWriter { writer ->
          XmlNodePrinter nodePrinter = new XmlNodePrinter(new PrintWriter(writer))
          nodePrinter.setPreserveWhitespace(true)
          nodePrinter.print(parsedProjectXml)
        }
      }
    }
  }

  /**
   * Attempt to determine if we are <a href="https://www.jetbrains.com/help/idea/gradle.html">importing from within
   * IntelliJ</a>, and if so, whether `resolveModulePerSourceSet` is enabled. We do this by checking the
   * `.idea/gradle.xml` file.
   * <p>
   * If there is no such file, this method returns true, to match previous behaviour.
   */
  private static boolean determineIfSeparateModulePerSourceSet(Project project) {
    // This gradle.xml file won't exist unless the project was imported from IntelliJ as a gradle project.
    // That is a different workflow from `./gradlew idea`.
    // See 'resolveModulePerSourceSet' definition in IntelliJ codebase:
    // https://github.com/JetBrains/intellij-community/blob/a38503dc884986dc66675986df691318adeb0efc/plugins/gradle/src/org/jetbrains/plugins/gradle/settings/GradleProjectSettings.java#L29
    File gradleConfigXml = project.rootProject.file(".idea/gradle.xml")
    return gradleConfigXml.exists() \
        ? new XmlSlurper().parse(gradleConfigXml)
            .component
            ?.find { it.@name == 'GradleSettings' }
            ?.option
            ?.find { it.@name == 'linkedExternalProjectsSettings' }
            ?.GradleProjectSettings
            ?.option
            ?.find { it.@name == 'resolveModulePerSourceSet' }
            ?.@value != "false" // Assume true if it's unset. this is IntelliJ's behaviour
        : true
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

  /**
   * @param usesSeparateModulePerSourceSet  only applicable if using a directory-based project and importing Gradle
   * from inside IntelliJ.
   */
  static void updateIdeaCompilerConfiguration(
      Project project,
      Node projectConfiguration,
      boolean usesSeparateModulePerSourceSet) {
    Object compilerConfiguration = projectConfiguration.component
            .find { it.@name == 'CompilerConfiguration' }

    if (compilerConfiguration == null) {
      throw new GradleException("Unable to find CompilerConfiguration element")
    }

    if (compilerConfiguration.annotationProcessing.isEmpty()) {
      new Node(compilerConfiguration, "annotationProcessing")
    }

    def dirPrefix = (usesSeparateModulePerSourceSet ? '../' : '')

    compilerConfiguration.annotationProcessing.replaceNode{
      annotationProcessing() {
        profile(default: 'true', name: 'Default', enabled: 'true') {
          sourceOutputDir(name: dirPrefix + getIdeaSourceOutputDir(project))
          sourceTestOutputDir(name: dirPrefix + getIdeaSourceTestOutputDir(project))
          outputRelativeToContentRoot(value: 'true')
          processorPath(useClasspath: 'true')
        }
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
}

class IdeaProcessorsExtension {
  Object outputDir
  Object testOutputDir
}
