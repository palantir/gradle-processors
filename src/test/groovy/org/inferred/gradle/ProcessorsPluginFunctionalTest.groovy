package org.inferred.gradle

import groovy.util.slurpersupport.NodeChild
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.hamcrest.CoreMatchers.containsString
import static org.hamcrest.CoreMatchers.not
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertThat
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
  public void testAnnotationProcessor() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        annotationProcessor 'com.google.auto.value:auto-value:1.0'
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
            .withGradleVersion("4.6")
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
    // Version 2.0.21 of Immutables generates code that FindBugs takes exception to.
    // The antipatterns 1.0 plugin causes issues if it cannot find supertypes.
    buildFile << """
      repositories {
        maven {
          url  "https://dl.bintray.com/palantir/releases"
        }
      }
      apply plugin: 'java'
      apply plugin: 'findbugs'
      apply plugin: 'org.inferred.processors'

      dependencies {
        findbugsPlugins 'com.palantir.antipatterns:antipatterns:1.0'
        processor 'org.immutables:value:2.0.21'
        compile 'com.google.code.findbugs:annotations:3.0.0'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();

        @SuppressFBWarnings("PT_EXTENDS_CONCRETE_TYPE")
        class Builder extends ImmutableMyClass.Builder {}
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("findbugsMain")
        .build()

    // Ensure no missing classes were reported
    def report = new File(testProjectDir.root, 'build/reports/findbugs/main.xml').text
    assertThat(report, not(containsString('<MissingClass>')))
  }

  @Test
  public void testFindBugsIntegrationImmutablesEnclosing() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'findbugs'
      apply plugin: 'org.inferred.processors'

      processors {
        suppressFindbugs = false
      }

      dependencies {
        processor 'com.google.code.findbugs:findbugs-annotations:3.0.1'
        processor 'org.immutables:value:2.4.0'

        compile 'com.fasterxml.jackson.core:jackson-databind:2.8.6'
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
      import org.immutables.value.Value;

      @Value.Enclosing
      public interface MyClass {
        @Value.Immutable
        @JsonDeserialize(as = ImmutableMyClass.Inner.class)
        interface Inner {
          @Value.Parameter String getValue();
        }
      }
    """

    GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withArguments("findbugsMain")
        .build()
  }

  @Test
  public void testJacocoIntegration() throws IOException {
    buildFile << """
      repositories {
        maven {
          url  "https://dl.bintray.com/palantir/releases"
        }
      }
      apply plugin: 'java'
      apply plugin: 'jacoco'
      apply plugin: 'org.inferred.processors'
      
      jacocoTestReport {
        reports {
          xml.enabled true
          html.enabled false
        }
      }

      dependencies {
        processor 'org.immutables:value:2.0.21'
        testCompile "junit:junit:4.12"
      }
    """

    new File(testProjectDir.newFolder('src', 'main', 'java'), 'MyClass.java') << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();

        class Builder extends ImmutableMyClass.Builder {}
      }
    """

    new File(testProjectDir.newFolder('src', 'test', 'java'), 'MyClassTest.java') << """
      import org.junit.Test;

      public class MyClassTest {
         @Test
         public void testBuilder() {
            new MyClass.Builder();
         }        
      }
    """

    GradleRunner.create()
            .withProjectDir(testProjectDir.getRoot())
            .withArguments("test", "jacocoTestReport")
            .build()

    // Ensure generated classes not included in JaCoCo report
    def report = new File(testProjectDir.root, 'build/reports/jacoco/test/jacocoTestReport.xml').text
    assertThat(report, not(containsString('name="Immutable')))
    assertThat(report, not(containsString('covered="0"')))
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
    txt = txt.substring(txt.indexOf(System.lineSeparator()) + System.lineSeparator().length(),
            txt.indexOf(System.lineSeparator() + System.lineSeparator()))
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
    assertEquals(result.task(":javadoc").getOutcome(), SUCCESS)
    assertTrue(stdErr.toString().readLines().grep { !it.contains("_JAVA_OPTIONS") }.isEmpty())
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
      org.eclipse.jdt.apt.genSrcDir=generated${File.separator}java
      org.eclipse.jdt.apt.reconcileEnabled=true
    """.replaceFirst('\n','').stripIndent()
    assertEquals(expected, prefsFile.text)
  }

  @Test
  public void testEclipseAptPrefsUsesProcessorsExtension() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }

      eclipse.processors {
        outputDir = 'something'
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

      idea.processors {
        outputDir = 'something'
        testOutputDir = 'something_else'
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

      idea.processors {
        outputDir = 'something'
        testOutputDir = 'something_else'
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
  public void testAnnotationProcessingInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../generated_src"/>
              <sourceTestOutputDir name="../generated_testSrc"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
  }

  @Test
  public void testCompilerXmlNotTouchedIfIdeaNotActive() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    def expected = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << expected

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    assertEquals(expected, xml)
  }

  @Test
  public void testNoAnnotationProcessingInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../generated_src"/>
              <sourceTestOutputDir name="../generated_testSrc"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
  }

  @Test
  public void testUserSpecifiedDirectoriesUsedInIdeaIprFile() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("idea", "--stacktrace")
            .build()

    def xml = new XmlSlurper().parse(testProjectDirRoot.toPath().resolve("${testProjectDirRoot.name}.ipr").toFile())
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == "Default" ? it : null }
    assertEquals(profile.sourceOutputDir.first().@name, "foo")
    assertEquals(profile.sourceTestOutputDir.first().@name, "bar")
  }

  @Test
  public void testUserSpecifiedDirectoriesUsedInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../foo"/>
              <sourceTestOutputDir name="../bar"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
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
        .withArguments("idea", "--stacktrace")
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

  /** @see https://github.com/palantir/gradle-processors/issues/28 */
  @Test
  public void testIdeaCompilerConfigurationUpdatedWithoutNeedToApplyIdeaPlugin() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'
    """

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../generated_src"/>
              <sourceTestOutputDir name="../generated_testSrc"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
  }

  /** @see https://github.com/palantir/gradle-processors/issues/53 */
  @Test
  public void testCompilerXmlModificationWhenIdeaPluginImportedLast() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'
      apply plugin: 'idea'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../foo"/>
              <sourceTestOutputDir name="../bar"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
  }

  /** @see https://github.com/palantir/gradle-processors/issues/53 */
  @Test
  public void testCompilerXmlModificationWhenIdeaPluginNotAppliedToRootProject() throws IOException {
    buildFile << """
      project(':A') {
        apply plugin: 'java'
        apply plugin: 'idea'
        apply plugin: 'org.inferred.processors'
      }
    """

    testProjectDir.newFolder('A')

    new File(testProjectDir.getRoot(), 'settings.gradle') << """
      include "A"
    """.stripIndent().trim()

    new File(testProjectDir.newFolder('.idea'), 'compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    File testProjectDirRoot = testProjectDir.getRoot()
    GradleRunner.create()
            .withProjectDir(testProjectDirRoot)
            .withArguments("-Didea.active=true", "--stacktrace")
            .build()

    def xml = testProjectDirRoot.toPath().resolve(".idea/compiler.xml").toFile().text.trim()

    def expected = """
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing>
            <profile default="true" name="Default" enabled="true">
              <sourceOutputDir name="../generated_src"/>
              <sourceTestOutputDir name="../generated_testSrc"/>
              <outputRelativeToContentRoot value="true"/>
              <processorPath useClasspath="true"/>
            </profile>
          </annotationProcessing>
        </component>
      </project>
    """.stripIndent().trim()

    assertEquals(expected, xml)
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
