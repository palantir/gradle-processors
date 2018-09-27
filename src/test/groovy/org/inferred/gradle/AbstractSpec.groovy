/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package org.inferred.gradle

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import nebula.test.multiproject.MultiProjectIntegrationHelper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class AbstractPluginTest extends Specification {

  @Rule
  TemporaryFolder folder = new TemporaryFolder()

  File buildFile
  File settingsFile
  MultiProjectIntegrationHelper multiProject
  File projectDir
  String gradleVersion

  def setup() {
    projectDir = folder.getRoot()
    buildFile = file('build.gradle')
    settingsFile = file('settings.gradle')
    println("Build directory: \n" + projectDir.absolutePath)
    multiProject = new MultiProjectIntegrationHelper(projectDir, settingsFile)
  }

  GradleRunner with(String... tasks) {
    def runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(tasks)
            .withPluginClasspath()
    if (gradleVersion != null) {
      runner.withGradleVersion(gradleVersion)
    }
    return runner
  }

  String exec(String task) {
    StringBuffer sout = new StringBuffer(), serr = new StringBuffer()
    Process proc = task.execute()
    proc.consumeProcessOutput(sout, serr)
    proc.waitFor()
    return sout.toString()
  }

  boolean execCond(String task) {
    StringBuffer sout = new StringBuffer(), serr = new StringBuffer()
    Process proc = task.execute()
    proc.consumeProcessOutput(sout, serr)
    proc.waitFor()
    return proc.exitValue() == 0
  }

  @CompileStatic(TypeCheckingMode.SKIP)
  protected File createFile(String path, File baseDir = projectDir) {
    File file = file(path, baseDir)
    assert !file.exists()
    file.parentFile.mkdirs()
    assert file.createNewFile()
    return file
  }

  protected File file(String path, File baseDir = projectDir) {
    def splitted = path.split('/')
    def directory = splitted.size() > 1 ? directory(splitted[0..-2].join('/'), baseDir) : baseDir
    def file = new File(directory, splitted[-1])
    return file
  }

  protected File directory(String path, File baseDir = projectDir) {
    return new File(baseDir, path).with {
      mkdirs()
      return it
    }
  }

  protected BuildResult runTasksSuccessfully(String... tasks) {
    with(tasks).build()
  }
}

