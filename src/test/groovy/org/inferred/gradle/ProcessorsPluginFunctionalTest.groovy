package org.inferred.gradle

import groovy.util.slurpersupport.NodeChild
import nebula.test.IntegrationSpec
import spock.lang.Unroll

class ProcessorsPluginFunctionalTest extends IntegrationSpec {

  void setup() {
    // Fork tests since we're changing system properties
    fork = true
    buildFile << """
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
        annotationProcessor 'com.google.auto.value:auto-value:1.0'
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

    gradleVersion = "4.6"

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
    gradleVersion << [null, "4.6"]
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

  void testFindBugsIntegration() throws IOException {
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

    file("src/main/java/MyClass.java") << """
      import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
      import org.immutables.value.Value;

      @Value.Immutable
      public interface MyClass {
        @Value.Parameter String getValue();

        @SuppressFBWarnings("PT_EXTENDS_CONCRETE_TYPE")
        class Builder extends ImmutableMyClass.Builder {}
      }
    """

    runTasksSuccessfully("findbugsMain")

    // Ensure no missing classes were reported
    def report = file('build/reports/findbugs/main.xml').text

    expect:
    !report.contains('<MissingClass>')
  }

  void testFindBugsIntegrationImmutablesEnclosing() throws IOException {
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

    file("src/main/java/MyClass.java") << """
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

    expect:
    runTasksSuccessfully("findbugsMain")
  }

  void testJacocoIntegration() throws IOException {
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
                void testBuilder() {
            new MyClass.Builder();
         }        
      }
    """

    expect:
    runTasksSuccessfully("test", "jacocoTestReport")

    // Ensure generated classes not included in JaCoCo report
    def report = file('build/reports/jacoco/test/jacocoTestReport.xml').text

    and:
    !report.contains('name="Immutable')
    !report.contains('covered="0"')
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

    def txt = runTasksSuccessfully("dependencies").standardOutput
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

    expect:
    def stdErr = runTasksSuccessfully("--info", "javadoc").standardError
    stdErr.readLines().grep { !it.contains("_JAVA_OPTIONS") }.isEmpty()
  }

  void testEclipseAptPrefs() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'eclipse'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
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
        processor 'org.immutables:value:2.0.21'
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

    File

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
        processor 'org.immutables:value:2.0.21'
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
        processor 'org.immutables:value:2.0.21'
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
        processor 'org.immutables:value:2.0.21'
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
        processor 'org.immutables:value:2.0.21'
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
        processor 'org.immutables:value:2.0.21'
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

  void testAnnotationProcessingInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'
    """

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
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

    println runTasksSuccessfully("--stacktrace", "--info").standardOutput

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

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
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
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == "Default" ? it : null }
    expect:
    profile.sourceOutputDir.first().@name == "foo"
    profile.sourceTestOutputDir.first().@name == "bar"
  }

  void testUserSpecifiedDirectoriesUsedInIdeaCompilerXml() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
  }

  void testOnlyApplyToSubProject() {
    buildFile << """
      apply plugin: 'idea'
    """

    addSubproject("projectA", """
      apply plugin: 'java'
    """)

    addSubproject("projectB", """
      apply plugin: 'java'
      apply plugin: 'idea'
      apply plugin: 'org.inferred.processors'

      dependencies {
        processor 'org.immutables:value:2.0.21'
      }
    """)

    runTasksSuccessfully("idea", "--stacktrace")

    def xml = new XmlSlurper().parse(file("${projectDir.name}.ipr"))
    def compilerConfiguration = xml.component.findResult { it.@name == "CompilerConfiguration" ? it : null }
    def profile = compilerConfiguration.annotationProcessing.profile.findResult { it.@name == "Default" ? it : null }

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

  /** @see https://github.com/palantir/gradle-processors/issues/28 */
  void testIdeaCompilerConfigurationUpdatedWithoutNeedToApplyIdeaPlugin() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'
    """

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
  }

  /** @see https://github.com/palantir/gradle-processors/issues/53 */
  void testCompilerXmlModificationWhenIdeaPluginImportedLast() throws IOException {
    buildFile << """
      apply plugin: 'java'
      apply plugin: 'org.inferred.processors'
      apply plugin: 'idea'

      idea.processors {
        outputDir = 'foo'
        testOutputDir = 'bar'
      }
    """

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
  }

  /** @see https://github.com/palantir/gradle-processors/issues/53 */
  void testCompilerXmlModificationWhenIdeaPluginNotAppliedToRootProject() throws IOException {
    addSubproject("A", """
        apply plugin: 'java'
        apply plugin: 'idea'
        apply plugin: 'org.inferred.processors'
    """.stripIndent())

    file('.idea/compiler.xml') << """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="CompilerConfiguration">
          <annotationProcessing/>
        </component>
      </project>
    """.trim()

    runTasksSuccessfully("-Didea.active=true", "--stacktrace")

    def xml = file(".idea/compiler.xml").text.trim()

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

    expect:
    expected == xml
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
