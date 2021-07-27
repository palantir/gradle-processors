package org.inferred.gradle

import groovy.util.slurpersupport.NodeChild
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Unroll

class ProcessorsPluginFunctionalTest extends AbstractPluginTest {

  def setup() {
    buildFile << """
      plugins {
        id 'org.inferred.processors' apply false
      }
      allprojects {
        repositories {
          mavenCentral()
        }
      }
    """.stripIndent()
  }

  void testJavaCompilation_javaFirst() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    file("src/main/java/MyClass.java") << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    expect:
    runTasksSuccessfully('compileJava')
  }

  void testAnnotationProcessor() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        annotationProcessor 'com.google.auto.value:auto-value:1.6.2'
        compileOnly 'com.google.auto.value:auto-value-annotations:1.6.2'
      }
    """

    file("src/main/java/MyClass.java") << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    gradleVersion = "6.9"

    expect:
    runTasksSuccessfully("compileJava")
  }

  void testJavaCompilation_processorsFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    file("src/main/java/MyClass.java") << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    expect:
    runTasksSuccessfully("compileJava")
  }

  @Unroll
  void 'testJavaTestCompilation for gradle #gradleVersion'() throws IOException {
    this.gradleVersion = gradleVersion
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    file('src/test/java/MyClass.java') << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    expect:
    runTasksSuccessfully("compileTestJava")

    where:
    gradleVersion << [null, "6.9"]
  }

  void testGroovyCompilationOfJavaFiles() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'groovy'

      dependencies {
        compile 'org.codehaus.groovy:groovy-all:2.3.10'
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    file('src/main/groovy/MyClass.java') << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();
        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    expect:
    runTasksSuccessfully("compileGroovy")
  }

  void testJacocoIntegration() throws IOException {
    buildFile << """
      repositories {
        mavenCentral()
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
        processor 'org.immutables:value:2.8.8'
        testCompile "junit:junit:4.12"
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();

        class Builder extends ImmutableMyClass.Builder {}
      }
    """

    file('src/test/java/MyClassTest.java') << """
      import org.junit.Test;

      public class MyClassTest {
         @Test
         public void testBuilder() {
            new MyClass.Builder();
         }        
      }
    """

    expect:
    def result = runTasksSuccessfully("test", "jacocoTestReport", "--info", "-s")
    result.task(':jacocoTestReport').outcome == TaskOutcome.SUCCESS

    // Ensure generated classes not included in JaCoCo report
    def report = file('build/reports/jacoco/test/jacocoTestReport.xml').text

    and:
//    !report.contains('name="Immutable')
    !report.contains('covered="0"')
  }

  void testJacocoIntegrationDoesNotBreakInGradle5() throws IOException {
    this.gradleVersion = gradleVersion
    buildFile << """
      repositories {
        mavenCentral()
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
        processor 'org.immutables:value:2.8.8'
        testCompile "junit:junit:4.12"
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();

        class Builder extends ImmutableMyClass.Builder {}
      }
    """

    file('src/test/java/MyClassTest.java') << """
      import org.junit.Test;

      public class MyClassTest {
         @Test
         public void testBuilder() {
            new MyClass.Builder();
         }        
      }
    """

    expect:
    def result = runTasksSuccessfully("test", "jacocoTestReport", "--info", "-s")
    result.task(':jacocoTestReport').outcome == TaskOutcome.SUCCESS

    // Ensure generated classes not included in JaCoCo report
    def report = file('build/reports/jacoco/test/jacocoTestReport.xml').text
    !report.empty

    and:
    // TODO(dsanduleac): re-enable these if we decide to implement this logic for gradle 5+
//    !report.contains('name="Immutable')
//    !report.contains('covered="0"')

    where:
    gradleVersion << ['6.9']
  }


  /** See <a href="https://github.com/palantir/gradle-processors/issues/3">issue #3</a> */
  void testProcessorJarsNotExported() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    file("src/main/java/MyClass.java") << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    def txt = runTasksSuccessfully("dependencies").output
    txt = txt.substring(txt.indexOf("runtime"))
    txt = txt.substring(txt.indexOf(System.lineSeparator()) + System.lineSeparator().length(),
            txt.indexOf(System.lineSeparator() + System.lineSeparator()))

    expect:
    "No dependencies" == txt
  }

  void testJavadoc() throws IOException {
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

    file("src/main/java/MyClass.java") << """
      import com.google.auto.value.AutoValue;

      @AutoValue
      public abstract class MyClass {
        public abstract int getValue();

        public static MyClass create(int value) {
          return new AutoValue_MyClass(value);
        }
      }
    """

    def stdErr = new StringWriter()

    expect:
    with("--info", "javadoc").forwardStdError(stdErr).build()
    stdErr.toString().readLines().grep { !it.contains("_JAVA_OPTIONS") }.isEmpty()
  }

  void testEclipseAptPrefs() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    runTasksSuccessfully("eclipseAptPrefs")

    def prefsFile = file(".settings/org.eclipse.jdt.apt.core.prefs")

    def expected = """
      eclipse.preferences.version=1
      org.eclipse.jdt.apt.aptEnabled=true
      org.eclipse.jdt.apt.genSrcDir=generated${File.separator}java
      org.eclipse.jdt.apt.reconcileEnabled=true
    """.replaceFirst('\n','').stripIndent()

    expect:
    expected == prefsFile.text
  }

  void testEclipseAptPrefsUsesProcessorsExtension() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }

      eclipse.processors {
        outputDir = 'something'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    runTasksSuccessfully("eclipseAptPrefs")

    def prefsFile = file(".settings/org.eclipse.jdt.apt.core.prefs")

    def expected = """
      eclipse.preferences.version=1
      org.eclipse.jdt.apt.aptEnabled=true
      org.eclipse.jdt.apt.genSrcDir=something
      org.eclipse.jdt.apt.reconcileEnabled=true
    """.replaceFirst('\n', '').stripIndent()

    expect:
    expected == prefsFile.text
  }

  void testCleanEclipseAptPrefs() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """

    runTasksSuccessfully("eclipseAptPrefs")
    runTasksSuccessfully("cleanEclipseAptPrefs")

    def prefsFile = new File(projectDir, ".settings/org.eclipse.jdt.apt.core.prefs")

    expect:
    !prefsFile.exists()
  }

  void testExistingGeneratedSourceDirectoriesAddedToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    // create generated source directories
    directory('generated_src')
    directory('generated_testSrc')

    runTasksSuccessfully("idea")

    // get source directories from iml file
    def xml = new XmlSlurper().parse(file("${projectDir.name}.iml"))
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/generated_src',
                    'file://$MODULE_DIR$/generated_testSrc'].toSet()
    expect:
    expected == sourceFolderUrls.toSet()
  }

  void testNonExistingGeneratedSourceDirectoriesAddedToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    runTasksSuccessfully("idea")

    // get source directories from iml file
    def xml = new XmlSlurper().parse(file("${projectDir.name}.iml"))
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/generated_src',
                    'file://$MODULE_DIR$/generated_testSrc'].toSet()
    expect:
    expected == sourceFolderUrls.toSet()
  }

  void testExistingDirectoriesAddedLazilyToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }

      idea.processors {
        outputDir = 'something'
        testOutputDir = 'something_else'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    // create generated source directories
    directory('something')
    directory('something_else')

    runTasksSuccessfully("idea")

    // get source directories from iml file
    def xml = new XmlSlurper().parse(file("${projectDir.name}.iml"))
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/something',
                    'file://$MODULE_DIR$/something_else'].toSet()
    expect:
    expected == sourceFolderUrls.toSet()
  }

  void testNonExistingDirectoriesAddedLazilyToIdeaIml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }

      idea.processors {
        outputDir = 'something'
        testOutputDir = 'something_else'
      }
    """

    file("src/main/java/MyClass.java") << """
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();
      }
    """

    runTasksSuccessfully("idea")

    // get source directories from iml file
    def xml = new XmlSlurper().parse(file("${projectDir.name}.iml"))
    def sourceFolders = xml.depthFirst().findAll { it.name() == "sourceFolder" }
    def sourceFolderUrls = sourceFolders.collect {
      ((NodeChild) it).attributes().get('url')
    }

    def expected = ['file://$MODULE_DIR$/src/main/java',
                    'file://$MODULE_DIR$/something',
                    'file://$MODULE_DIR$/something_else'].toSet()
    expect:
    expected == sourceFolderUrls.toSet()
  }

  void testAnnotationProcessingInIdeaIpr() throws IOException {
    def subproject = """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
      
      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """.stripIndent()
    multiProject.addSubproject('foo', subproject)
    multiProject.addSubproject('bar', subproject)

    buildFile << "apply plugin: 'idea'"

    when:
    runTasksSuccessfully("idea", "--stacktrace")

    then:
    def xml = new XmlSlurper().parse(file("${projectDir.name}.ipr"))
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profiles = compilerConfiguration.annotationProcessing.profile

    expect:
    profiles.each {
      with(it) {
        it.processorPath.first().@useClasspath == 'false'
        it.processorPath.first().entry.collect { it.@name.text() }.any { it.contains 'value-2.8.8.jar' }
      }
    }

    with(profiles.find { it.@name == ':foo' }) { profile ->
      profile.module.first().@name == 'foo'
    }
    with(profiles.find { it.@name == ':bar' }) { profile ->
      profile.module.first().@name == 'bar'
    }
  }

  void testAnnotationProcessingInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    def compilerXml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.stripIndent().trim()
    file('.idea/compiler.xml') << compilerXml

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

    expect:
    compilerXml == xml
  }

  void testCompilerXmlNotTouchedIfIdeaNotActive() throws IOException {
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

    file('.idea/compiler.xml') << expected

    runTasksSuccessfully("--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

    expect:
    expected == xml
  }

  void testNoAnnotationProcessingInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    def compilerXml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
        </component>
      </project>
    """.stripIndent().trim()
    file('.idea/compiler.xml') << compilerXml

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

    expect:
    compilerXml == xml
  }

  void testUserSpecifiedDirectoriesUsedInIdeaIprFile() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    runTasksSuccessfully("idea", "--stacktrace")

    def xml = new XmlSlurper().parse(file("${projectDir.name}.ipr"))
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == ":" ? it : null }
    expect:
    profile.sourceOutputDir.first().@name == "foo"
    profile.sourceTestOutputDir.first().@name == "bar"
  }

  void testOnlyApplyToSubProject() {
    buildFile << """
      apply plugin: 'idea'
    """

    multiProject.addSubproject("projectA", """
      apply plugin: 'java'
    """)

    multiProject.addSubproject("projectB", """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.8.8'
      }
    """)

    runTasksSuccessfully("idea", "--stacktrace")

    def xml = new XmlSlurper().parse(file("${projectDir.name}.ipr"))
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == ":projectB" ? it : null }

    expect:
    profile.sourceOutputDir.first().@name == "generated_src"
  }

  /** See <a href="https://github.com/palantir/gradle-processors/issues/12">issue #12</a> */
  void testEclipseClasspathModified_javaPluginFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'java'
      apply plugin: 'eclipse'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    expect:
    runTasksSuccessfully("eclipse")
    assertAutoValueInFile(file(".classpath"))
    assertAutoValueInFile(file(".factorypath"))
  }

  /** See <a href="https://github.com/palantir/gradle-processors/issues/12">issue #12</a> */
  void testEclipseClasspathModified_eclipsePluginFirst() throws IOException {
    buildFile << """
      apply plugin: 'org.inferred.processors'
      apply plugin: 'eclipse'
      apply plugin: 'java'

      dependencies {
        processor 'com.google.auto.value:auto-value:1.0'
      }
    """

    expect:
    runTasksSuccessfully("eclipse")
    assertAutoValueInFile(file(".classpath"))
    assertAutoValueInFile(file(".factorypath"))
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

}
