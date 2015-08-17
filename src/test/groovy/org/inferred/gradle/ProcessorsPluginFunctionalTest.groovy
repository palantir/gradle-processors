package org.inferred.gradle

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Collections

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue

public class ProcessorsPluginFunctionalTest {

  @Rule public final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile

  @Before
  public void setup() throws IOException {
    buildFile = testProjectDir.newFile("build.gradle")
    writeBuildscript()
  }

  @Test
  public void testJavaCompilation_javaFirst() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("compileJava")
        .build()
  }

  @Test
  public void testJavaCompilation_processorsFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("compileJava")
        .build()
  }

  @Test
  public void testJavaTestCompilation() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    new File(testProjectDir.newFolder('src', 'test', 'java'), 'MyClass.java') << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("compileTestJava")
        .build()
  }

  private void writeBuildscript() {
    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException("Did not find plugin classpath resource, run `testClasses` build task.")
    }

    def pluginClasspath = pluginClasspathResource.readLines()
        .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
        .collect { "'$it'" }
        .join(", ")

    buildFile << """
      buildscript {
        dependencies {
          classpath files($pluginClasspath)
        }
      }

      repositories {
        mavenCentral()
        maven {
          url "https://repository-achartengine.forge.cloudbees.com/snapshot/"
        }
      }
    """
  }
}
