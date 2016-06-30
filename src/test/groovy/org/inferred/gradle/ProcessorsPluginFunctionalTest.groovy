package org.inferred.gradle

import groovy.util.slurpersupport.NodeChild
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse

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

  @Test
  public void testGroovyCompilationOfJavaFiles() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'groovy'

      dependencies {
        compile 'org.codehaus.groovy:groovy-all:2.3.10'
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'groovy'), 'MyClass.java') << """
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
        .withArguments("compileGroovy")
        .build()
  }

  @Test
  public void testFindBugsIntegration() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'findbugs'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("findbugsMain")
        .build()
  }

  /** @see https://github.com/palantir/gradle-processors/issues/3 */
  @Test
  public void testProcessorJarsNotExported() throws IOException {
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

    String txt = GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("dependencies")
        .build()
        .getOutput()
    txt = txt.substring(txt.indexOf("runtime"))
    txt = txt.substring(txt.indexOf("\n") + 1, txt.indexOf("\n\n"))
    assertEquals("No dependencies", txt)
  }

  @Test
  public void testJavadoc() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }

      javadoc {
        options.encoding = 'UTF-8'
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

    StringWriter stdErr = new StringWriter()
    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("--info", "javadoc")
        .forwardStdError(stdErr)
        .build()
    assertEquals(result.task(":javadoc").getOutcome(), SUCCESS);
    assertEquals("", stdErr.toString());
  }

  @Test
  public void testEclipseAptPrefs() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }

      processors {
        sourceOutputDir = 'something'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()

    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("eclipseAptPrefs")
            .build()

    def prefsFile = new File(testProjectDirRoot, ".settings/org.eclipse.jdt.apt.core.prefs")

    def expected = """
      eclipse.preferences.version=1
      org.eclipse.jdt.apt.aptEnabled=true
      org.eclipse.jdt.apt.genSrcDir=something
      org.eclipse.jdt.apt.reconcileEnabled=true
    """.replaceFirst('\n','').stripIndent()
    assertEquals(expected, prefsFile.text)
  }

  @Test
  public void testCleanEclipseAptPrefs() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()

    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("eclipseAptPrefs")
            .build()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("cleanEclipseAptPrefs")
            .build()

    def prefsFile = new File(testProjectDirRoot, ".settings/org.eclipse.jdt.apt.core.prefs")

    assertFalse(prefsFile.exists())
  }

  @Test
  public void testExistingGeneratedSourceDirectoriesAddedToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    // create generated source directories
    testProjectDir.newFolder('generated_src')
    testProjectDir.newFolder('generated_testSrc')

    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("idea")
            .build()

    // get source directories from iml file
    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.iml").toFile())
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/generated_src',
                    'file://$MODULE_DIR$/generated_testSrc'].toSet()
    assertEquals(expected, sourceFolderUrls.toSet())
  }

  @Test
  public void testNonExistingGeneratedSourceDirectoriesAddedToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("idea")
            .build()

    // get source directories from iml file
    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.iml").toFile())
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/generated_src',
                    'file://$MODULE_DIR$/generated_testSrc'].toSet()
    assertEquals(expected, sourceFolderUrls.toSet())
  }

  @Test
  public void testExistingDirectoriesAddedLazilyToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }

      processors {
        sourceOutputDir = 'something'
        testSourceOutputDir = 'something_else'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    // create generated source directories
    testProjectDir.newFolder('something')
    testProjectDir.newFolder('something_else')

    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("idea")
            .build()

    // get source directories from iml file
    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.iml").toFile())
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/something',
                    'file://$MODULE_DIR$/something_else'].toSet()
    assertEquals(expected, sourceFolderUrls.toSet())
  }

  @Test
  public void testNonExistingDirectoriesAddedLazilyToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }

      processors {
        sourceOutputDir = 'something'
        testSourceOutputDir = 'something_else'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("idea")
            .build()

    // get source directories from iml file
    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.iml").toFile())
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/something',
                    'file://$MODULE_DIR$/something_else'].toSet()
    assertEquals(expected, sourceFolderUrls.toSet())
  }

  @Test
  public void testOnlyApplyToSubProject() {
    testProjectDir.newFolder("projectA")
    testProjectDir.newFolder("projectB")

    File projectABuildFile = testProjectDir.newFile("projectA/build.gradle")
    File projectBBuildFile = testProjectDir.newFile("projectB/build.gradle")
    File settingsFile = testProjectDir.newFile("settings.gradle")

    buildFile << """
      apply plugin: 'idea'
    """

    settingsFile << """
      include 'projectA'
      include 'projectB'
    """

    projectABuildFile << """
      apply plugin: 'java'
    """

    projectBBuildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
        .withProjectDir(testProjectDirRoot)
        .withArguments("idea")
        .build()

    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.ipr").toFile())
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == "Default" ? it : null }
    assertEquals(profile.sourceOutputDir.first().@name, "generated_src")
  }

  /** @see https://github.com/palantir/gradle-processors/issues/12 */
  @Test
  public void testEclipseClasspathModified_javaPluginFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'
      apply plugin: 'eclipse'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    GradleRunner runner = GradleRunner.create()
    runner
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("eclipse")
        .build()
    assertAutoValueInFile(new File(runner.projectDir, ".classpath"))
    assertAutoValueInFile(new File(runner.projectDir, ".factorypath"))
  }

  /** @see https://github.com/palantir/gradle-processors/issues/12 */
  @Test
  public void testEclipseClasspathModified_eclipsePluginFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'eclipse'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    GradleRunner runner = GradleRunner.create()
    runner
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("eclipse")
        .build()
    assertAutoValueInFile(new File(runner.projectDir, ".classpath"))
    assertAutoValueInFile(new File(runner.projectDir, ".factorypath"))
  }

  private void assertAutoValueInFile(File file) {
    if (!file.any { it.contains("auto-value-1.0.jar") }) {
      println "====== $file.name ============================================================"
      file.eachLine() { line ->
          println line
      }
      throw new AssertionError("auto-value-1.0.jar not in $file.name")
    }
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
