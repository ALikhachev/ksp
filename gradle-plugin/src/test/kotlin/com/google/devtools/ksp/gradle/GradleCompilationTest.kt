/*
 * Copyright 2020 Google LLC
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.devtools.ksp.gradle

import com.google.common.truth.Truth.assertThat
import com.google.devtools.ksp.gradle.processor.TestSymbolProcessorProvider
import com.google.devtools.ksp.gradle.testing.DependencyDeclaration.Companion.artifact
import com.google.devtools.ksp.gradle.testing.DependencyDeclaration.Companion.module
import com.google.devtools.ksp.gradle.testing.KspIntegrationTestRule
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleCompilationTest {
    @Rule
    @JvmField
    val tmpDir = TemporaryFolder()

    @Rule
    @JvmField
    val testRule = KspIntegrationTestRule(tmpDir)

    @Test
    fun errorMessageFailsCompilation() {
        testRule.setupAppAsJvmApp()
        testRule.appModule.dependencies.add(
            module(configuration = "ksp", testRule.processorModule)
        )
        testRule.appModule.addSource(
            "Foo.kt",
            """
            class Foo {
            }
            """.trimIndent()
        )
        class ErrorReporting(private val logger: KSPLogger) : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> {
                logger.error("my processor failure")
                return emptyList()
            }
        }

        class Provider : TestSymbolProcessorProvider({ env -> ErrorReporting(env.logger) })

        testRule.addProvider(Provider::class)
        val failure = testRule.runner()
            .withArguments("app:assemble")
            .buildAndFail()
        assertThat(failure.output).contains("my processor failure")
    }

    @Test
    fun applicationCanAccessGeneratedCode() {
        testRule.setupAppAsJvmApp()
        testRule.appModule.dependencies.add(
            module(configuration = "ksp", testRule.processorModule)
        )
        testRule.appModule.addSource(
            "Foo.kt",
            """
            class Foo {
                val x = ToBeGenerated()
            }
            """.trimIndent()
        )
        testRule.appModule.addSource(
            "JavaSrc.java",
            """
            class JavaSrc {
                ToBeGenerated x;
            }
            """.trimIndent()
        )
        class MyProcessor(private val codeGenerator: CodeGenerator) : SymbolProcessor {
            var count = 0
            override fun process(resolver: Resolver): List<KSAnnotated> {
                if (count == 0) {
                    codeGenerator.createNewFile(Dependencies.ALL_FILES, "", "Generated").use {
                        it.writer(Charsets.UTF_8).use {
                            it.write("class ToBeGenerated")
                        }
                    }
                    count += 1
                }
                return emptyList()
            }
        }

        class Provider : TestSymbolProcessorProvider({ env -> MyProcessor(env.codeGenerator) })

        testRule.addProvider(Provider::class)

        testRule.runner()
            .withDebug(true)
            .withArguments("app:assemble")
            .forwardOutput()
            .build()
    }

    @Test
    fun testCommandLineArgumentProvider() {
        // FIXME
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        testRule.setupAppAsAndroidApp()
        testRule.appModule.addSource("Foo.kt", "class Foo")
        testRule.appModule.addSource(
            "Entity.kt",
            """
            import androidx.room.Entity
            import androidx.room.PrimaryKey
            import androidx.room.ColumnInfo

            @Entity
            data class User(
                @PrimaryKey val uid: Int,
                @ColumnInfo(name = "first_name") val firstName: String?,
                @ColumnInfo(name = "last_name") val lastName: String?
            )
            """.trimIndent()
        )
        testRule.appModule.addSource(
            "UserDao.kt",
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface UserDao {
                @Query("SELECT * FROM User")
                fun getAll(): List<User>
            }
            """.trimIndent()
        )
        testRule.appModule.addSource(
            "Database.kt",
            """
            import androidx.room.Database
            import androidx.room.RoomDatabase

            @Database(entities = [User::class], version = 1)
            abstract class Database : RoomDatabase() {
                abstract fun userDao(): UserDao
            }
            """.trimIndent()
        )
        testRule.appModule.dependencies.addAll(
            listOf(
                artifact(configuration = "ksp", "androidx.room:room-compiler:2.4.2"),
                artifact(configuration = "implementation", "androidx.room:room-runtime:2.4.2")
            )
        )
        testRule.appModule.buildFileAdditions.add(
            """
                ksp {
                    arg(Provider(project.layout.projectDirectory.dir("schemas").asFile))
                }
                class Provider(roomOutputDir: File) : CommandLineArgumentProvider {

                    @OutputDirectory
                    val outputDir = roomOutputDir

                    override fun asArguments(): Iterable<String> {
                        return listOf(
                            "room.schemaLocation=${'$'}{outputDir.path}",
                            "room.generateKotlin=true"
                        )
                    }
                }
                tasks.withType<com.google.devtools.ksp.gradle.KspTask>().configureEach {
                    doFirst {
                        options.get().forEach { option ->
                            println("${'$'}{option.key}=${'$'}{option.value}")
                        }
                    }
                }

            """.trimIndent()
        )
        val result = testRule.runner().withArguments(":app:assembleDebug").build()
        val pattern1 = Regex.escape("apoption=room.schemaLocation=")
        val pattern2 = Regex.escape("${testRule.appModule.moduleRoot}/schemas")
        assertThat(result.output).containsMatch("$pattern1\\S*$pattern2")
        assertThat(result.output).contains("apoption=room.generateKotlin=true")
        val schemasFolder = testRule.appModule.moduleRoot.resolve("schemas")
        assertThat(result.task(":app:kspDebugKotlin")!!.outcome).isEquivalentAccordingToCompareTo(TaskOutcome.SUCCESS)
        assertThat(schemasFolder.exists()).isTrue()
        assertThat(schemasFolder.resolve("Database/1.json").exists()).isTrue()
    }

    @Test
    fun invalidArguments() {
        testRule.setupAppAsJvmApp()
        testRule.appModule.addSource("Foo.kt", "class Foo")
        testRule.appModule.buildFileAdditions.add(
            """
            ksp {
                arg(Provider())
            }
            class Provider() : CommandLineArgumentProvider {
                override fun asArguments(): Iterable<String> {
                    return listOf(
                        "invalid"
                    )
                }
            }
            """.trimIndent()
        )
        testRule.appModule.dependencies.addAll(
            listOf(
                artifact(configuration = "ksp", "androidx.room:room-compiler:2.4.2")
            )
        )

        val result = testRule.runner().withArguments(":app:assemble").buildAndFail()
        assertThat(result.output).contains("KSP apoption does not match \\S+=\\S+: invalid")
    }

    @Test
    fun commandLineArgumentIsIncludedInApoptionsWhenAddedInKspTask() {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        testRule.setupAppAsAndroidApp()
        testRule.appModule.dependencies.addAll(
            listOf(
                artifact(configuration = "ksp", "androidx.room:room-compiler:2.4.2")
            )
        )
        testRule.appModule.buildFileAdditions.add(
            """
                 class Provider(roomOutputDir: File) : CommandLineArgumentProvider {

                     @OutputDirectory
                     val outputDir = roomOutputDir

                     override fun asArguments(): Iterable<String> {
                         return listOf(
                             "room.schemaLocation=${'$'}{outputDir.path}"
                         )
                     }
                 }
                 afterEvaluate {
                   tasks.withType<com.google.devtools.ksp.gradle.KspTask>().configureEach {
                     val destination = project.layout.projectDirectory.dir("schemas-${'$'}{this.name}")
                     commandLineArgumentProviders.add(Provider(destination.asFile))

                     options.get().forEach { option ->
                       println("${'$'}{option.key}=${'$'}{option.value}")
                     }
                     commandLineArgumentProviders.get().forEach { commandLine ->
                       println("commandLine=${'$'}{commandLine.asArguments()}")
                     }
                   }
                 }
            """.trimIndent()
        )
        val result = testRule.runner().withDebug(true).withArguments(":app:assembleDebug").build()
        val pattern1 = Regex.escape("apoption=room.schemaLocation=")
        val pattern2 = Regex.escape(testRule.appModule.moduleRoot.resolve("schemas-kspDebugKotlin").path)
        val pattern3 = Regex.escape("commandLine=[")
        assertThat(result.output).containsMatch("$pattern1\\S*$pattern2")
        assertThat(result.output).containsMatch("$pattern3\\S*$pattern2")
    }

    @Test
    fun kspLibrariesHaveNoGenerated() {
        testRule.setupAppAsJvmApp()
        testRule.appModule.addSource("Foo.kt", "class Foo")
        testRule.appModule.buildFileAdditions.add(
            """
                 afterEvaluate {
                   tasks.withType<com.google.devtools.ksp.gradle.KspTaskJvm>().configureEach {
                     libraries.files.forEach {
                       println("HAS LIBRARY: ${'$'}{it.path}")
                     }
                   }
                 }
            """.trimIndent()
        )

        testRule.appModule.dependencies.add(
            module(configuration = "ksp", testRule.processorModule)
        )

        class DummyProcessor : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
        }

        class Provider : TestSymbolProcessorProvider({ env -> DummyProcessor() })

        testRule.addProvider(Provider::class)

        val result = testRule.runner().withArguments(":app:assemble").build()
        assertThat(result.output).contains("HAS LIBRARY: ")
        assertThat(result.output).doesNotContain("app/build/generated/ksp/main/classes")
    }
}
