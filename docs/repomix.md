# Repository Compiler Plugin Template Kotlin k2

This file is a merged representation of the entire codebase, combined into a single document by Repomix.
The content has been processed where security check has been disabled.

# File Summary

## Purpose
This file contains a packed representation of the entire repository's contents.
It is designed to be easily consumable by AI systems for analysis, code review,
or other automated processes.

## File Format
The content is organized as follows:
1. This summary section
2. Repository information
3. Directory structure
4. Repository files (if enabled)
5. Multiple file entries, each consisting of:
   a. A header with the file path (## File: path/to/file)
   b. The full contents of the file in a code block

## Usage Guidelines
- This file should be treated as read-only. Any changes should be made to the
  original repository files, not this packed version.
- When processing this file, use the file path to distinguish
  between different files in the repository.
- Be aware that this file may contain sensitive information. Handle it with
  the same level of security as you would the original repository.

## Notes
- Some files may have been excluded based on .gitignore rules and Repomix's configuration
- Binary files are not included in this packed representation. Please refer to the Repository Structure section for a complete list of file paths, including binary files
- Files matching patterns in .gitignore are excluded
- Files matching default ignore patterns are excluded
- Security check has been disabled - content may contain sensitive information
- Files are sorted by Git change count (files with more changes are at the bottom)

# Directory Structure
```
.github/
  workflows/
    build.yml
compiler-plugin/
  resources/
    META-INF/
      services/
        org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
        org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
  src/
    org/
      jetbrains/
        kotlin/
          compiler/
            plugin/
              template/
                fir/
                  SimpleClassGenerator.kt
                ir/
                  AbstractTransformerForGenerator.kt
                  SimpleIrBodyGenerator.kt
                  SimpleIrGenerationExtension.kt
                SimpleCommandLineProcessor.kt
                SimplePluginComponentRegistrar.kt
                SimplePluginRegistrar.kt
  test-fixtures/
    org/
      jetbrains/
        kotlin/
          compiler/
            plugin/
              template/
                runners/
                  AbstractJvmBoxTest.kt
                  AbstractJvmDiagnosticTest.kt
                services/
                  ExtensionRegistrarConfigurator.kt
                  PluginAnnotationsProvider.kt
                GenerateTests.kt
  test-gen/
    org/
      jetbrains/
        kotlin/
          compiler/
            plugin/
              template/
                runners/
                  JvmBoxTestGenerated.java
                  JvmDiagnosticTestGenerated.java
  testData/
    box/
      anotherBoxTest.fir.ir.txt
      anotherBoxTest.fir.txt
      anotherBoxTest.kt
      simple.fir.ir.txt
      simple.fir.txt
      simple.kt
    diagnostics/
      anotherDiagnosticTest.fir.txt
      anotherDiagnosticTest.kt
      simple.fir.txt
      simple.kt
  build.gradle.kts
gradle/
  wrapper/
    gradle-wrapper.properties
gradle-plugin/
  src/
    org/
      jetbrains/
        kotlin/
          compiler/
            plugin/
              template/
                SimpleGradleExtension.kt
                SimpleGradlePlugin.kt
  build.gradle.kts
plugin-annotations/
  api/
    plugin-annotations.api
  src/
    commonMain/
      kotlin/
        org/
          jetbrains/
            kotlin/
              compiler/
                plugin/
                  template/
                    SomeAnnotation.kt
  build.gradle.kts
.gitignore
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
LICENSE.txt
README.md
settings.gradle.kts
```

# Files

## File: .github/workflows/build.yml
```yaml
on:
  pull_request: {}
  push:
    branches:
      - master

env:
  GRADLE_OPTS: "-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.daemon=false -Dkotlin.incremental=false"

jobs:
  build:
    runs-on: macos-latest

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build --continue

      - uses: mikepenz/action-junit-report@v5
        if: success() || failure()
        with:
          report_paths: '**/build/test-results/test/TEST-*.xml'
```

## File: compiler-plugin/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
```
org.jetbrains.kotlin.compiler.plugin.template.SimpleCommandLineProcessor
```

## File: compiler-plugin/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
```
org.jetbrains.kotlin.compiler.plugin.template.SimplePluginComponentRegistrar
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/fir/SimpleClassGenerator.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.*

/*
 * Generates top level class
 *
 * public final class foo.bar.MyClass {
 *     fun foo(): String = "Hello world"
 * }
 */
class SimpleClassGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
    companion object {
        val MY_CLASS_ID = ClassId(FqName.fromSegments(listOf("foo", "bar")), Name.identifier("MyClass"))

        val FOO_ID = CallableId(MY_CLASS_ID, Name.identifier("foo"))
    }

    @ExperimentalTopLevelDeclarationsGenerationApi
    override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
        if (classId != MY_CLASS_ID) return null
        val klass = createTopLevelClass(MY_CLASS_ID, Key)
        return klass.symbol
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val classId = context.owner.classId
        require(classId == MY_CLASS_ID)
        val constructor = createConstructor(context.owner, Key, /*generateDelegatedNoArgConstructorCall = true*/)
        return listOf(constructor.symbol)
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        val owner = context?.owner ?: return emptyList()
        val function = createMemberFunction(owner, Key, callableId.callableName, returnType = session.builtinTypes.stringType.coneType)
        return listOf(function.symbol)
    }

    override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> {
        return if (classSymbol.classId == MY_CLASS_ID) {
            setOf(FOO_ID.callableName, SpecialNames.INIT)
        } else {
            emptySet()
        }
    }

    @ExperimentalTopLevelDeclarationsGenerationApi
    override fun getTopLevelClassIds(): Set<ClassId> {
        return setOf(MY_CLASS_ID)
    }

    override fun hasPackage(packageFqName: FqName): Boolean {
        return packageFqName == MY_CLASS_ID.packageFqName
    }

    object Key : GeneratedDeclarationKey()
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/ir/AbstractTransformerForGenerator.kt
```kotlin
/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compiler.plugin.template.ir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

abstract class AbstractTransformerForGenerator(protected val context: IrPluginContext) : IrElementVisitorVoid {
    protected val irFactory = context.irFactory
    protected val irBuiltIns = context.irBuiltIns

    abstract fun interestedIn(key: GeneratedDeclarationKey?): Boolean
    abstract fun generateBodyForFunction(function: IrSimpleFunction, key: GeneratedDeclarationKey?): IrBody?
    abstract fun generateBodyForConstructor(constructor: IrConstructor, key: GeneratedDeclarationKey?): IrBody?

    final override fun visitElement(element: IrElement) {
        when (element) {
            is IrDeclaration,
            is IrFile,
            is IrModuleFragment -> element.acceptChildrenVoid(this)
            else -> {}
        }
    }

    final override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        val origin = declaration.origin
        if (origin !is IrDeclarationOrigin.GeneratedByPlugin || !interestedIn(origin.pluginKey)) return
        require(declaration.body == null)
        declaration.body = generateBodyForFunction(declaration, origin.pluginKey)
    }

    final override fun visitConstructor(declaration: IrConstructor) {
        val origin = declaration.origin
        if (origin !is IrDeclarationOrigin.GeneratedByPlugin || !interestedIn(origin.pluginKey)) return
        require(declaration.body == null)
        declaration.body = generateBodyForConstructor(declaration, origin.pluginKey)
    }

    // ------------------------ utilities ------------------------

    protected fun generateDefaultBodyForMaterializeFunction(function: IrSimpleFunction): IrBody? {
        val constructedType = function.returnType as? IrSimpleType ?: return null
        val constructedClassSymbol = constructedType.classifier
        val constructedClass = constructedClassSymbol.owner as? IrClass ?: return null
        val constructor = constructedClass.primaryConstructor ?: return null
        val constructorCall = IrConstructorCallImpl(
            -1,
            -1,
            constructedType,
            constructor.symbol,
            typeArgumentsCount = 0,
            constructorTypeArgumentsCount = 0,
        )
        val returnStatement = IrReturnImpl(-1, -1, irBuiltIns.nothingType, function.symbol, constructorCall)
        return irFactory.createBlockBody(-1, -1, listOf(returnStatement))
    }

    protected fun generateBodyForDefaultConstructor(declaration: IrConstructor): IrBody? {
        val type = declaration.returnType as? IrSimpleType ?: return null

        val delegatingAnyCall = IrDelegatingConstructorCallImpl(
            -1,
            -1,
            irBuiltIns.anyType,
            irBuiltIns.anyClass.owner.primaryConstructor?.symbol ?: return null,
            typeArgumentsCount = 0,
        )

        val initializerCall = IrInstanceInitializerCallImpl(
            -1,
            -1,
            (declaration.parent as? IrClass)?.symbol ?: return null,
            type
        )

        return irFactory.createBlockBody(-1, -1, listOf(delegatingAnyCall, initializerCall))
    }
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/ir/SimpleIrBodyGenerator.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.ir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl

class SimpleIrBodyGenerator(pluginContext: IrPluginContext) : AbstractTransformerForGenerator(pluginContext) {
    override fun interestedIn(key: GeneratedDeclarationKey?): Boolean {
        return key == SimpleClassGenerator.Key
    }

    override fun generateBodyForFunction(function: IrSimpleFunction, key: GeneratedDeclarationKey?): IrBody {
        require(function.name == SimpleClassGenerator.FOO_ID.callableName)
        val const = IrConstImpl(-1, -1, irBuiltIns.stringType, IrConstKind.String, value = "Hello world")
        val returnStatement = IrReturnImpl(-1, -1, irBuiltIns.nothingType, function.symbol, const)
        return irFactory.createBlockBody(-1, -1, listOf(returnStatement))
    }

    override fun generateBodyForConstructor(constructor: IrConstructor, key: GeneratedDeclarationKey?): IrBody? {
        return generateBodyForDefaultConstructor(constructor)
    }
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/ir/SimpleIrGenerationExtension.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

class SimpleIrGenerationExtension: IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformers = listOf(SimpleIrBodyGenerator(pluginContext))
        for (transformer in transformers) {
            moduleFragment.acceptChildrenVoid(transformer)
        }
    }
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/SimpleCommandLineProcessor.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

@Suppress("unused") // Used via reflection.
class SimpleCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = emptyList()

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        error("Unexpected config option: '${option.optionName}'")
    }
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/SimplePluginComponentRegistrar.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.template.ir.SimpleIrGenerationExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

class SimplePluginComponentRegistrar: CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(SimplePluginRegistrar())
        IrGenerationExtension.registerExtension(SimpleIrGenerationExtension())
    }
}
```

## File: compiler-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/SimplePluginRegistrar.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class SimplePluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SimpleClassGenerator
    }
}
```

## File: compiler-plugin/test-fixtures/org/jetbrains/kotlin/compiler/plugin/template/runners/AbstractJvmBoxTest.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.runners

import org.jetbrains.kotlin.compiler.plugin.template.services.ExtensionRegistrarConfigurator
import org.jetbrains.kotlin.compiler.plugin.template.services.PluginAnnotationsProvider
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJvmBoxTest : AbstractFirBlackBoxCodegenTestBase(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)

        with(builder) {
            /*
             * Containers of different directives, which can be used in tests:
             * - ModuleStructureDirectives
             * - LanguageSettingsDirectives
             * - DiagnosticsDirectives
             * - FirDiagnosticsDirectives
             * - CodegenTestDirectives
             * - JvmEnvironmentConfigurationDirectives
             *
             * All of them are located in `org.jetbrains.kotlin.test.directives` package
             */
            defaultDirectives {
                +CodegenTestDirectives.DUMP_IR
                +FirDiagnosticsDirectives.FIR_DUMP
                +JvmEnvironmentConfigurationDirectives.FULL_JDK

                +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
            }

            useConfigurators(
                ::PluginAnnotationsProvider,
                ::ExtensionRegistrarConfigurator
            )
        }
    }
}
```

## File: compiler-plugin/test-fixtures/org/jetbrains/kotlin/compiler/plugin/template/runners/AbstractJvmDiagnosticTest.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.runners

import org.jetbrains.kotlin.compiler.plugin.template.services.ExtensionRegistrarConfigurator
import org.jetbrains.kotlin.compiler.plugin.template.services.PluginAnnotationsProvider
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)

        with(builder) {
            /*
             * Containers of different directives, which can be used in tests:
             * - ModuleStructureDirectives
             * - LanguageSettingsDirectives
             * - DiagnosticsDirectives
             * - FirDiagnosticsDirectives
             *
             * All of them are located in `org.jetbrains.kotlin.test.directives` package
             */
            defaultDirectives {
                +FirDiagnosticsDirectives.FIR_DUMP
                +JvmEnvironmentConfigurationDirectives.FULL_JDK

                +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
            }

            useConfigurators(
                ::PluginAnnotationsProvider,
                ::ExtensionRegistrarConfigurator
            )
        }
    }
}
```

## File: compiler-plugin/test-fixtures/org/jetbrains/kotlin/compiler/plugin/template/services/ExtensionRegistrarConfigurator.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.template.SimplePluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.template.ir.SimpleIrGenerationExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

class ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration
    ) {
        FirExtensionRegistrarAdapter.registerExtension(SimplePluginRegistrar())
        IrGenerationExtension.registerExtension(SimpleIrGenerationExtension())
    }
}
```

## File: compiler-plugin/test-fixtures/org/jetbrains/kotlin/compiler/plugin/template/services/PluginAnnotationsProvider.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

class PluginAnnotationsProvider(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    companion object {
        private val annotationsRuntimeClasspath =
            System.getProperty("annotationsRuntime.classpath")?.split(File.pathSeparator)?.map(::File)
                ?: error("Unable to get a valid classpath from 'annotationsRuntime.classpath' property")
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        for (file in annotationsRuntimeClasspath) {
            configuration.addJvmClasspathRoot(file)
        }
    }
}
```

## File: compiler-plugin/test-fixtures/org/jetbrains/kotlin/compiler/plugin/template/GenerateTests.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmBoxTest
import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmDiagnosticTest
import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testDataRoot = "compiler-plugin/testData", testsRoot = "compiler-plugin/test-gen") {
            testClass<AbstractJvmDiagnosticTest> {
                model("diagnostics")
            }

            testClass<AbstractJvmBoxTest> {
                model("box")
            }
        }
    }
}
```

## File: compiler-plugin/test-gen/org/jetbrains/kotlin/compiler/plugin/template/runners/JvmBoxTestGenerated.java
```java
package org.jetbrains.kotlin.compiler.plugin.template.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.compiler.plugin.template.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler-plugin/testData/box")
@TestDataPath("$PROJECT_ROOT")
public class JvmBoxTestGenerated extends AbstractJvmBoxTest {
  @Test
  public void testAllFilesPresentInBox() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-plugin/testData/box"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
  }

  @Test
  @TestMetadata("anotherBoxTest.kt")
  public void testAnotherBoxTest() {
    runTest("compiler-plugin/testData/box/anotherBoxTest.kt");
  }

  @Test
  @TestMetadata("simple.kt")
  public void testSimple() {
    runTest("compiler-plugin/testData/box/simple.kt");
  }
}
```

## File: compiler-plugin/test-gen/org/jetbrains/kotlin/compiler/plugin/template/runners/JvmDiagnosticTestGenerated.java
```java
package org.jetbrains.kotlin.compiler.plugin.template.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.compiler.plugin.template.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler-plugin/testData/diagnostics")
@TestDataPath("$PROJECT_ROOT")
public class JvmDiagnosticTestGenerated extends AbstractJvmDiagnosticTest {
  @Test
  public void testAllFilesPresentInDiagnostics() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("compiler-plugin/testData/diagnostics"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
  }

  @Test
  @TestMetadata("anotherDiagnosticTest.kt")
  public void testAnotherDiagnosticTest() {
    runTest("compiler-plugin/testData/diagnostics/anotherDiagnosticTest.kt");
  }

  @Test
  @TestMetadata("simple.kt")
  public void testSimple() {
    runTest("compiler-plugin/testData/diagnostics/simple.kt");
  }
}
```

## File: compiler-plugin/testData/box/anotherBoxTest.fir.ir.txt
```
FILE fqName:<root> fileName:/anotherBoxTest.kt
  FUN name:box visibility:public modality:FINAL <> () returnType:kotlin.String
    BLOCK_BODY
      VAR name:list type:kotlin.collections.List<kotlin.String> [val]
        CALL 'public final fun listOf <T> (vararg elements: T of kotlin.collections.listOf): kotlin.collections.List<T of kotlin.collections.listOf> declared in kotlin.collections' type=kotlin.collections.List<kotlin.String> origin=null
          <T>: kotlin.String
          elements: VARARG type=kotlin.Array<out kotlin.String> varargElementType=kotlin.String
            CONST String type=kotlin.String value="aaa"
            CONST String type=kotlin.String value="bb"
            CONST String type=kotlin.String value="c"
      VAR name:result type:kotlin.Int [val]
        CALL 'public final fun sum (): kotlin.Int declared in kotlin.collections' type=kotlin.Int origin=null
          $receiver: CALL 'public final fun map <T, R> (transform: kotlin.Function1<T of kotlin.collections.map, R of kotlin.collections.map>): kotlin.collections.List<R of kotlin.collections.map> declared in kotlin.collections' type=kotlin.collections.List<kotlin.Int> origin=null
            <T>: kotlin.String
            <R>: kotlin.Int
            $receiver: GET_VAR 'val list: kotlin.collections.List<kotlin.String> declared in <root>.box' type=kotlin.collections.List<kotlin.String> origin=null
            transform: FUN_EXPR type=kotlin.Function1<kotlin.String, kotlin.Int> origin=LAMBDA
              FUN LOCAL_FUNCTION_FOR_LAMBDA name:<anonymous> visibility:local modality:FINAL <> (it:kotlin.String) returnType:kotlin.Int
                VALUE_PARAMETER name:it index:0 type:kotlin.String
                BLOCK_BODY
                  RETURN type=kotlin.Nothing from='local final fun <anonymous> (it: kotlin.String): kotlin.Int declared in <root>.box'
                    CALL 'public open fun <get-length> (): kotlin.Int declared in kotlin.String' type=kotlin.Int origin=GET_PROPERTY
                      $this: GET_VAR 'it: kotlin.String declared in <root>.box.<anonymous>' type=kotlin.String origin=null
      RETURN type=kotlin.Nothing from='public final fun box (): kotlin.String declared in <root>'
        WHEN type=kotlin.String origin=IF
          BRANCH
            if: CALL 'public final fun EQEQ (arg0: kotlin.Any?, arg1: kotlin.Any?): kotlin.Boolean declared in kotlin.internal.ir' type=kotlin.Boolean origin=EQEQ
              arg0: GET_VAR 'val result: kotlin.Int declared in <root>.box' type=kotlin.Int origin=null
              arg1: CONST Int type=kotlin.Int value=6
            then: CONST String type=kotlin.String value="OK"
          BRANCH
            if: CONST Boolean type=kotlin.Boolean value=true
            then: STRING_CONCATENATION type=kotlin.String
              CONST String type=kotlin.String value="Fail: "
              GET_VAR 'val result: kotlin.Int declared in <root>.box' type=kotlin.Int origin=null
FILE fqName:foo.bar fileName:__GENERATED DECLARATIONS__.kt
  CLASS GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] CLASS name:MyClass modality:FINAL visibility:public superTypes:[kotlin.Any]
    $this: VALUE_PARAMETER INSTANCE_RECEIVER name:<this> type:foo.bar.MyClass
    CONSTRUCTOR GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] visibility:public <> () returnType:foo.bar.MyClass
      BLOCK_BODY
        DELEGATING_CONSTRUCTOR_CALL 'public constructor <init> () declared in kotlin.Any'
        INSTANCE_INITIALIZER_CALL classDescriptor='CLASS GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] CLASS name:MyClass modality:FINAL visibility:public superTypes:[kotlin.Any]' type=foo.bar.MyClass
    FUN FAKE_OVERRIDE name:equals visibility:public modality:OPEN <> ($this:kotlin.Any, other:kotlin.Any?) returnType:kotlin.Boolean [fake_override,operator]
      overridden:
        public open fun equals (other: kotlin.Any?): kotlin.Boolean declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
      VALUE_PARAMETER name:other index:0 type:kotlin.Any?
    FUN FAKE_OVERRIDE name:hashCode visibility:public modality:OPEN <> ($this:kotlin.Any) returnType:kotlin.Int [fake_override]
      overridden:
        public open fun hashCode (): kotlin.Int declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
    FUN FAKE_OVERRIDE name:toString visibility:public modality:OPEN <> ($this:kotlin.Any) returnType:kotlin.String [fake_override]
      overridden:
        public open fun toString (): kotlin.String declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
    FUN GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] name:foo visibility:public modality:FINAL <> ($this:foo.bar.MyClass) returnType:kotlin.String
      $this: VALUE_PARAMETER name:<this> type:foo.bar.MyClass
      BLOCK_BODY
        RETURN type=kotlin.Nothing from='public final fun foo (): kotlin.String declared in foo.bar.MyClass'
          CONST String type=kotlin.String value="Hello world"
```

## File: compiler-plugin/testData/box/anotherBoxTest.fir.txt
```
FILE: anotherBoxTest.kt
    public final fun box(): R|kotlin/String| {
        lval list: R|kotlin/collections/List<kotlin/String>| = R|kotlin/collections/listOf|<R|kotlin/String|>(vararg(String(aaa), String(bb), String(c)))
        lval result: R|kotlin/Int| = R|<local>/list|.R|kotlin/collections/map|<R|kotlin/String|, R|kotlin/Int|>(<L> = map@fun <anonymous>(it: R|kotlin/String|): R|kotlin/Int| <inline=Inline, kind=UNKNOWN>  {
            ^ R|<local>/it|.R|kotlin/String.length|
        }
        ).R|kotlin/collections/sum|()
        ^box when () {
            ==(R|<local>/result|, Int(6)) ->  {
                String(OK)
            }
            else ->  {
                <strcat>(String(Fail: ), R|<local>/result|)
            }
        }

    }
FILE: __GENERATED DECLARATIONS__.kt
    package foo.bar

    public final class MyClass : R|kotlin/Any| {
        public final fun foo(): R|kotlin/String|

        public constructor(): R|foo/bar/MyClass|

    }
```

## File: compiler-plugin/testData/box/anotherBoxTest.kt
```kotlin
// WITH_STDLIB

fun box(): String {
    val list = listOf("aaa", "bb", "c")
    val result = list.map { it.length }.sum()
    return if (result == 6) "OK" else "Fail: $result"
}
```

## File: compiler-plugin/testData/box/simple.fir.ir.txt
```
FILE fqName:foo.bar fileName:/simple.kt
  FUN name:box visibility:public modality:FINAL <> () returnType:kotlin.String
    BLOCK_BODY
      VAR name:result type:kotlin.String [val]
        CALL 'public final fun foo (): kotlin.String declared in foo.bar.MyClass' type=kotlin.String origin=null
          $this: CONSTRUCTOR_CALL 'public constructor <init> () declared in foo.bar.MyClass' type=foo.bar.MyClass origin=null
      RETURN type=kotlin.Nothing from='public final fun box (): kotlin.String declared in foo.bar'
        WHEN type=kotlin.String origin=IF
          BRANCH
            if: CALL 'public final fun EQEQ (arg0: kotlin.Any?, arg1: kotlin.Any?): kotlin.Boolean declared in kotlin.internal.ir' type=kotlin.Boolean origin=EQEQ
              arg0: GET_VAR 'val result: kotlin.String declared in foo.bar.box' type=kotlin.String origin=null
              arg1: CONST String type=kotlin.String value="Hello world"
            then: BLOCK type=kotlin.String origin=null
              CONST String type=kotlin.String value="OK"
          BRANCH
            if: CONST Boolean type=kotlin.Boolean value=true
            then: BLOCK type=kotlin.String origin=null
              STRING_CONCATENATION type=kotlin.String
                CONST String type=kotlin.String value="Fail: "
                GET_VAR 'val result: kotlin.String declared in foo.bar.box' type=kotlin.String origin=null
FILE fqName:foo.bar fileName:__GENERATED DECLARATIONS__.kt
  CLASS GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] CLASS name:MyClass modality:FINAL visibility:public superTypes:[kotlin.Any]
    $this: VALUE_PARAMETER INSTANCE_RECEIVER name:<this> type:foo.bar.MyClass
    CONSTRUCTOR GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] visibility:public <> () returnType:foo.bar.MyClass
      BLOCK_BODY
        DELEGATING_CONSTRUCTOR_CALL 'public constructor <init> () declared in kotlin.Any'
        INSTANCE_INITIALIZER_CALL classDescriptor='CLASS GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] CLASS name:MyClass modality:FINAL visibility:public superTypes:[kotlin.Any]' type=foo.bar.MyClass
    FUN FAKE_OVERRIDE name:equals visibility:public modality:OPEN <> ($this:kotlin.Any, other:kotlin.Any?) returnType:kotlin.Boolean [fake_override,operator]
      overridden:
        public open fun equals (other: kotlin.Any?): kotlin.Boolean declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
      VALUE_PARAMETER name:other index:0 type:kotlin.Any?
    FUN FAKE_OVERRIDE name:hashCode visibility:public modality:OPEN <> ($this:kotlin.Any) returnType:kotlin.Int [fake_override]
      overridden:
        public open fun hashCode (): kotlin.Int declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
    FUN FAKE_OVERRIDE name:toString visibility:public modality:OPEN <> ($this:kotlin.Any) returnType:kotlin.String [fake_override]
      overridden:
        public open fun toString (): kotlin.String declared in kotlin.Any
      $this: VALUE_PARAMETER name:<this> type:kotlin.Any
    FUN GENERATED[org.jetbrains.kotlin.compiler.plugin.template.fir.SimpleClassGenerator.Key] name:foo visibility:public modality:FINAL <> ($this:foo.bar.MyClass) returnType:kotlin.String
      $this: VALUE_PARAMETER name:<this> type:foo.bar.MyClass
      BLOCK_BODY
        RETURN type=kotlin.Nothing from='public final fun foo (): kotlin.String declared in foo.bar.MyClass'
          CONST String type=kotlin.String value="Hello world"
```

## File: compiler-plugin/testData/box/simple.fir.txt
```
FILE: simple.kt
    package foo.bar

    public final fun box(): R|kotlin/String| {
        lval result: R|kotlin/String| = R|foo/bar/MyClass|().R|foo/bar/MyClass.foo|()
        ^box when () {
            ==(R|<local>/result|, String(Hello world)) ->  {
                String(OK)
            }
            else ->  {
                <strcat>(String(Fail: ), R|<local>/result|)
            }
        }

    }
FILE: __GENERATED DECLARATIONS__.kt
    package foo.bar

    public final class MyClass : R|kotlin/Any| {
        public final fun foo(): R|kotlin/String|

        public constructor(): R|foo/bar/MyClass|

    }
```

## File: compiler-plugin/testData/box/simple.kt
```kotlin
package foo.bar

fun box(): String {
    val result = MyClass().foo()
    return if (result == "Hello world") { "OK" } else { "Fail: $result" }
}
```

## File: compiler-plugin/testData/diagnostics/anotherDiagnosticTest.fir.txt
```
Module: lib
FILE: foo.kt
    package foo

    public final fun takeInt(x: R|kotlin/Int|): R|kotlin/Unit| {
    }
    public final fun test(): R|kotlin/Unit| {
        R|foo/takeInt|(Int(10))
    }
FILE: __GENERATED DECLARATIONS__.kt
    package foo.bar

    public final class MyClass : R|kotlin/Any| {
        public final fun foo(): R|kotlin/String|

        public constructor(): R|foo/bar/MyClass|

    }
Module: main
FILE: bar.kt
    package bar

    public final fun test(): R|kotlin/Unit| {
        R|foo/takeInt|(Int(10))
        R|foo/takeInt<Inapplicable(INAPPLICABLE): foo/takeInt>#|(String(Hello))
    }
FILE: __GENERATED DECLARATIONS__.kt
    package foo.bar

    public final class MyClass : R|kotlin/Any| {
        public final fun foo(): R|kotlin/String|

        public constructor(): R|foo/bar/MyClass|

    }
```

## File: compiler-plugin/testData/diagnostics/anotherDiagnosticTest.kt
```kotlin
// RUN_PIPELINE_TILL: FRONTEND

// MODULE: lib
// FILE: foo.kt
package foo

fun takeInt(x: Int) {}

fun test() {
    takeInt(10)
}

// MODULE: main(lib)
// FILE: bar.kt
package bar

import foo.takeInt

fun test() {
    takeInt(10)
    takeInt(<!ARGUMENT_TYPE_MISMATCH!>"Hello"<!>)
}
```

## File: compiler-plugin/testData/diagnostics/simple.fir.txt
```
FILE: simple.kt
    package foo.bar

    @R|org/jetbrains/kotlin/compiler/plugin/template/SomeAnnotation|() public final fun test(): R|kotlin/Unit| {
        lval s: R|kotlin/String| = R|foo/bar/MyClass|().R|foo/bar/MyClass.foo|()
        R|<local>/s|.<Unresolved name: inc>#()
    }
FILE: __GENERATED DECLARATIONS__.kt
    package foo.bar

    public final class MyClass : R|kotlin/Any| {
        public final fun foo(): R|kotlin/String|

        public constructor(): R|foo/bar/MyClass|

    }
```

## File: compiler-plugin/testData/diagnostics/simple.kt
```kotlin
// RUN_PIPELINE_TILL: FRONTEND

package foo.bar

import org.jetbrains.kotlin.compiler.plugin.template.SomeAnnotation

@SomeAnnotation
fun test() {
    val s = MyClass().foo()
    s.<!UNRESOLVED_REFERENCE!>inc<!>() // should be an error
}
```

## File: compiler-plugin/build.gradle.kts
```
plugins {
    kotlin("jvm")
    `java-test-fixtures`
    id("com.github.gmazzo.buildconfig")
    idea
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("test", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
}

idea {
    module.generatedSourceDirs.add(projectDir.resolve("test-gen"))
}

val annotationsRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }

dependencies {
    compileOnly(kotlin("compiler"))

    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(kotlin("compiler-internal-test-framework"))
    testFixturesApi(kotlin("compiler"))

    annotationsRuntimeClasspath(project(":plugin-annotations"))

    // Dependencies required to run the internal test framework.
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly(kotlin("reflect"))
    testRuntimeOnly(kotlin("test"))
    testRuntimeOnly(kotlin("script-runtime"))
    testRuntimeOnly(kotlin("annotations-jvm"))
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }

    packageName(group.toString())
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")
}

tasks.test {
    dependsOn(annotationsRuntimeClasspath)

    useJUnitPlatform()
    workingDir = rootDir

    systemProperty("annotationsRuntime.classpath", annotationsRuntimeClasspath.asPath)

    // Properties required to run the internal test framework.
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.compiler.plugin.template.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = project.configurations
        .testRuntimeClasspath.get()
        .files
        .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}
```

## File: gradle/wrapper/gradle-wrapper.properties
```
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

## File: gradle-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/SimpleGradleExtension.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.gradle.api.model.ObjectFactory

open class SimpleGradleExtension(objectFactory: ObjectFactory) {
}
```

## File: gradle-plugin/src/org/jetbrains/kotlin/compiler/plugin/template/SimpleGradlePlugin.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.compiler.plugin.template.BuildConfig.ANNOTATIONS_LIBRARY_COORDINATES
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Used via reflection.
class SimpleGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        target.extensions.create("simplePlugin", SimpleGradleExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        kotlinCompilation.dependencies { implementation(ANNOTATIONS_LIBRARY_COORDINATES) }
        if (kotlinCompilation.implementationConfigurationName == "metadataCompilationImplementation") {
            project.dependencies.add("commonMainImplementation", ANNOTATIONS_LIBRARY_COORDINATES)
        }

        return project.provider {
            val extension = project.extensions.getByType(SimpleGradleExtension::class.java)

            emptyList()
        }
    }
}
```

## File: gradle-plugin/build.gradle.kts
```
plugins {
    kotlin("jvm")
    id("com.github.gmazzo.buildconfig")
    id("java-gradle-plugin")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))

    testImplementation(kotlin("test-junit5"))
}

buildConfig {
    packageName(project.group.toString())

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")

    val pluginProject = project(":compiler-plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${pluginProject.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${pluginProject.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${pluginProject.version}\"")

    val annotationsProject = project(":plugin-annotations")
    buildConfigField(
        type = "String",
        name = "ANNOTATIONS_LIBRARY_COORDINATES",
        expression = "\"${annotationsProject.group}:${annotationsProject.name}:${annotationsProject.version}\""
    )
}

gradlePlugin {
    plugins {
        create("SimplePlugin") {
            id = rootProject.group.toString()
            displayName = "SimplePlugin"
            description = "SimplePlugin"
            implementationClass = "org.demiurg906.kotlin.plugin.SimpleGradlePlugin"
        }
    }
}
```

## File: plugin-annotations/api/plugin-annotations.api
```
public abstract interface annotation class org/jetbrains/kotlin/compiler/plugin/template/SomeAnnotation : java/lang/annotation/Annotation {
}
```

## File: plugin-annotations/src/commonMain/kotlin/org/jetbrains/kotlin/compiler/plugin/template/SomeAnnotation.kt
```kotlin
package org.jetbrains.kotlin.compiler.plugin.template

public annotation class SomeAnnotation
```

## File: plugin-annotations/build.gradle.kts
```
@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

kotlin {
    explicitApi()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    js().nodejs()

    jvm()

    linuxArm64()
    linuxX64()

    macosArm64()
    macosX64()

    mingwX64()

    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()

    wasmJs().nodejs()
    wasmWasi().nodejs()

    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()
    watchosX64()

    applyDefaultHierarchyTemplate()
}
```

## File: .gitignore
```
.idea/*
!.idea/externalDependencies.xml
!.idea/kotlinTestDataPluginTestDataPaths.xml
.gradle/
.kotlin/
build/
out/
```

## File: build.gradle.kts
```
plugins {
    kotlin("multiplatform") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
    id("com.github.gmazzo.buildconfig") version "5.6.5"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3" apply false
}

allprojects {
    group = "org.jetbrains.kotlin.compiler.plugin.template"
    version = "0.1.0-SNAPSHOT"
}
```

## File: gradle.properties
```
kotlin.code.style=official

org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
```

## File: gradlew
```
#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   Gradle start up script for POSIX generated by Gradle.
#
#   Important for running:
#
#   (1) You need a POSIX-compliant shell to run this script. If your /bin/sh is
#       noncompliant, but you have some other compliant shell such as ksh or
#       bash, then to run this script, type that shell name before the whole
#       command line, like:
#
#           ksh Gradle
#
#       Busybox and similar reduced shells will NOT work, because this script
#       requires all of these POSIX shell features:
#         * functions;
#         * expansions «$var», «${var}», «${var:-default}», «${var+SET}»,
#           «${var#prefix}», «${var%suffix}», and «$( cmd )»;
#         * compound commands having a testable exit status, especially «case»;
#         * various built-in commands including «command», «set», and «ulimit».
#
#   Important for patching:
#
#   (2) This script targets any POSIX shell, so it avoids extensions provided
#       by Bash, Ksh, etc; in particular arrays are avoided.
#
#       The "traditional" practice of packing multiple parameters into a
#       space-separated string is a well documented source of bugs and security
#       problems, so this is (mostly) avoided, by progressively accumulating
#       options in "$@", and eventually passing that to Java.
#
#       Where the inherited environment variables (DEFAULT_JVM_OPTS, JAVA_OPTS,
#       and GRADLE_OPTS) rely on word-splitting, this is performed explicitly;
#       see the in-line comments for details.
#
#       There are tweaks for specific operating systems such as AIX, CygWin,
#       Darwin, MinGW, and NonStop.
#
#   (3) This script is generated from the Groovy template
#       https://github.com/gradle/gradle/blob/HEAD/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/unixStartScript.txt
#       within the Gradle project.
#
#       You can find Gradle at https://github.com/gradle/gradle/.
#
##############################################################################

# Attempt to set APP_HOME

# Resolve links: $0 may be a link
app_path=$0

# Need this for daisy-chained symlinks.
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# This is normally unused
# shellcheck disable=SC2034
APP_BASE_NAME=${0##*/}
# Discard cd standard output in case $CDPATH is set (https://github.com/gradle/gradle/issues/25036)
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac

CLASSPATH="\\\"\\\""


# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# Increase the maximum file descriptors if we can.
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # In POSIX sh, ulimit -H is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # In POSIX sh, ulimit -n is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# Collect all arguments for the java command, stacking in reverse order:
#   * args from the command line
#   * the main class name
#   * -classpath
#   * -D...appname settings
#   * --module-path (only if needed)
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and GRADLE_OPTS environment variables.

# For Cygwin or MSYS, switch paths to Windows format before running java
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )
    CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # don't mess with options #(
              /?*)  t=${arg#/} t=/${t%%/*}              # looks like a POSIX filepath
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # Roll the args list around exactly as many times as the number of
        # args, so each arg winds up back in the position where it started, but
        # possibly modified.
        #
        # NB: a `for` loop captures its iteration list before it begins, so
        # changing the positional parameters here affects neither the number of
        # iterations, nor the values presented in `arg`.
        shift                   # remove old arg
        set -- "$@" "$arg"      # push replacement arg
    done
fi


# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Collect all arguments for the java command:
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and optsEnvironmentVar are not allowed to contain shell fragments,
#     and any embedded shellness will be escaped.
#   * For example: A user cannot expect ${Hostname} to be expanded, as it is an environment variable and will be
#     treated as '${Hostname}' itself on the command line.

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "$CLASSPATH" \
        -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        "$@"

# Stop when "xargs" is not available.
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# Use "xargs" to parse quoted args.
#
# With -n1 it outputs one arg per line, with the quotes and backslashes removed.
#
# In Bash we could simply go:
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# but POSIX shell has neither arrays nor command substitution, so instead we
# post-process each arg (as a line of input to sed) to backslash-escape any
# character that might be a shell metacharacter, then use eval to reverse
# that process (while maintaining the separation between arguments), and wrap
# the whole thing up as a single "set" statement.
#
# This will of course break if any of these variables contains a newline or
# an unmatched quote.
#

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
```

## File: gradlew.bat
```
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
```

## File: LICENSE.txt
```
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

## File: README.md
```markdown
# Kotlin Compiler Plugin template

This is a template project for writing a compiler plugin for the Kotlin compiler.

## Details

This project has three modules:
- The [`:compiler-plugin`](compiler-plugin/src) module contains the compiler plugin itself.
- The [`:plugin-annotations`](plugin-annotations/src/commonMain/kotlin) module contains annotations which can be used in
user code for interacting with compiler plugin.
- The [`:gradle-plugin`](gradle-plugin/src) module contains a simple Gradle plugin to add the compiler plugin and
annotation dependency to a Kotlin project. 

Extension point registration:
- K2 Frontend (FIR) extensions can be registered in `SimplePluginRegistrar`.
- All other extensions (including K1 frontend and backend) can be registered in `SimplePluginComponentRegistrar`.

## Tests

The [Kotlin compiler test framework][test-framework] is set up for this project.
To create a new test, add a new `.kt` file in a [compiler-plugin/testData](compiler-plugin/testData) sub-directory:
`testData/box` for codegen tests and `testData/diagnostics` for diagnostics tests.
The generated JUnit 5 test classes will be updated automatically when tests are next run.
They can be manually updated with the `generateTests` Gradle task as well.
To aid in running tests, it is recommended to install the [Kotlin Compiler DevKit][test-plugin] IntelliJ plugin,
which is pre-configured in this repository.

[//]: # (Links)

[test-framework]: https://github.com/JetBrains/kotlin/blob/2.1.20/compiler/test-infrastructure/ReadMe.md
[test-plugin]: https://github.com/JetBrains/kotlin-compiler-devkit
```

## File: settings.gradle.kts
```
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "compiler-plugin-template"

include("compiler-plugin")
include("gradle-plugin")
include("plugin-annotations")
```
