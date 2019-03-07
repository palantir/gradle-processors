/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 */

package org.inferred.gradle


import nebula.test.IntegrationTestKitSpec
import nebula.test.multiproject.MultiProjectIntegrationHelper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

class AbstractPluginTest extends IntegrationTestKitSpec {

  MultiProjectIntegrationHelper multiProject
  String gradleVersion

  def setup() {
    keepFiles = true
    // Necessary when using gradle 5+
    settingsFile.createNewFile()
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

  /** Intentionally overwritten as {@link nebula.test.BaseIntegrationSpec#file} creates the file */
  @Override
  protected File file(String path, File baseDir = projectDir) {
    def splitted = path.split('/')
    def directory = splitted.size() > 1 ? directory(splitted[0..-2].join('/'), baseDir) : baseDir
    def file = new File(directory, splitted[-1])
    return file
  }

  protected BuildResult runTasksSuccessfully(String... tasks) {
    with(tasks).build()
  }
}

