package org.inferred.gradle

import org.gradle.api.GradleException

import java.io.InputStreamReader

import groovy.text.SimpleTemplateEngine

import org.gradle.api.DomainObjectCollection
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.specs.Spec
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin

class ProcessorsPlugin implements Plugin<Project> {

  void apply(Project project) {

    project.configurations.create('processor')

    project.extensions.create('processors', ProcessorsExtension)
    project.processors {
      // Used by Eclipse and IDEA
      sourceOutputDir = 'generated_src'

      // Used by IDEA (Eclipse does not compile test sources separately)
      testSourceOutputDir = 'generated_testSrc'
    }

    /**** javac, groovy, etc. *********************************************************************/
    project.plugins.withType(JavaPlugin, { plugin ->
      project.sourceSets.each { it.compileClasspath += project.configurations.processor }
      project.compileJava.dependsOn project.task('processorPath', {
        doLast {
          String path = getProcessors(project).getAsPath()
          project.compileJava.options.compilerArgs += ["-processorpath", path]
        }
      })
      project.javadoc.dependsOn project.task('javadocProcessors', {
        doLast {
          Set<File> path = getProcessors(project).files
          project.javadoc.options.classpath += path
        }
      })
    })

    /**** Eclipse *********************************************************************************/
    project.plugins.withType(EclipsePlugin, { plugin ->
      project.eclipse {
        classpath.plusConfigurations += [project.configurations.processor]
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
          [
            sourceOutputDir: project.processors.sourceOutputDir,
            deps: project.configurations.processor
          ]
      )
      project.tasks.eclipse.dependsOn project.tasks.eclipseAptPrefs
      project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseAptPrefs

      templateTask(
          project,
          'eclipseFactoryPath',
          'org/inferred/gradle/factorypath.template',
          '.factorypath',
          [deps: project.configurations.processor]
      )
      project.tasks.eclipse.dependsOn project.tasks.eclipseFactoryPath
      project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseFactoryPath
    })

    /**** IntelliJ ********************************************************************************/
    project.plugins.withType(IdeaPlugin, { plugin ->
      if (project.idea.module.scopes.PROVIDED != null) {
        project.idea.module.scopes.PROVIDED.plus += [project.configurations.processor]
      }

      project.idea.module.generatedSourceDirs += project.file(project.processors.sourceOutputDir)
      project.idea.module.sourceDirs += project.file(project.processors.sourceOutputDir)
      project.idea.module.generatedSourceDirs += project.file(project.processors.testSourceOutputDir)
      project.idea.module.testSourceDirs += project.file(project.processors.testSourceOutputDir)

      // Root project configuration
      if (project.idea.project != null) {
        project.idea.project.ipr {
          withXml {
            updateIdeaCompilerConfiguration(project, node)
          }
        }
      }

      // If the project uses .idea directory structure, update compiler.xml directly
      File ideaCompilerXml = project.file('.idea/compiler.xml')
      if (ideaCompilerXml.isFile()) {
        Node parsedProjectXml = (new XmlParser()).parse(ideaCompilerXml)
        updateIdeaCompilerConfiguration(project, parsedProjectXml)
        ideaCompilerXml.withWriter { writer ->
          XmlNodePrinter nodePrinter = new XmlNodePrinter(new PrintWriter(writer))
          nodePrinter.setPreserveWhitespace(true)
          nodePrinter.print(parsedProjectXml)
        }
      }
    })

    /**** FindBugs ********************************************************************************/
    project.tasks.withType(FindBugs, { task -> task.doFirst {
      // Exclude generated sources from FindBugs' traversal.
      // This trick relies on javac putting the generated .java files next to the .class files.
      def generatedSources = task.classes.filter {
        it.path.endsWith '.java'
      }
      task.classes = task.classes.filter {
        File javaFile = new File(it.path
            .replaceFirst(/\$\w+\.class$/, '')
            .replaceFirst(/\.class$/, '')
            + '.java')
        boolean isGenerated = generatedSources.contains(javaFile)
        return !isGenerated
      }
    }})
  }

  /** Runs {@code action} on element {@code name} in {@code collection} whenever it is added. */
  private static <T> void withName(
      NamedDomainObjectCollection<T> collection, String name, Closure action) {
    T object = collection.findByName(name)
    if (object != null) {
      action.call(object)
    } else {
      collection.whenObjectAdded { o ->
        String oName = collection.getNamer().determineName(o)
        if (oName == name) {
          action.call(o)
        }
      }
    }
  }

  static FileCollection getProcessors(Project project) {
    ResolvedConfiguration config = project.configurations.processor.resolvedConfiguration
    return project.files(config.getFiles({ d -> true } as Spec<Object>))
  }

  static void templateTask(project, taskName, templateFilename, outputFilename, binding) {
    def outputFile = new File(project.projectDir, outputFilename)
    def cleanTaskName = "clean" + taskName.substring(0, 1).toUpperCase() + taskName.substring(1)
    project.task(taskName, {
      binding.each{ k, v -> inputs.property k, v }
      outputs.file outputFile
      doLast {
        outputFile.parentFile.mkdirs()
        def stream = getClass().classLoader.getResourceAsStream templateFilename
        try {
          def reader = new InputStreamReader(stream, "UTF-8")
          def template = new SimpleTemplateEngine().createTemplate(reader)
          def writable = template.make binding
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

    project.task(cleanTaskName, {
      doLast {
        outputFile.delete()
      }
    })
  }

  static void updateIdeaCompilerConfiguration(Project project, Node projectConfiguration) {
    Object compilerConfiguration = projectConfiguration.component
            .find { it.@name == 'CompilerConfiguration' }

    if (compilerConfiguration == null) {
      throw new GradleException("Unable to find CompilerConfiguration element")
    }

    compilerConfiguration.annotationProcessing.replaceNode{
      annotationProcessing() {
        profile(default: 'true', name: 'Default', enabled: 'true') {
          sourceOutputDir(name: project.processors.sourceOutputDir)
          sourceTestOutputDir(name: project.processors.testSourceOutputDir)
          outputRelativeToContentRoot(value: 'true')
          processorPath(useClasspath: 'true')
        }
      }
    }
  }

}

class ProcessorsExtension {
  String sourceOutputDir
  String testSourceOutputDir
}
