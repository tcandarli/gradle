package org.gradle.plugins.performance

import accessors.groovy
import accessors.java
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.tasks.DefaultTaskContainer
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.internal.hash.HashUtil
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.testing.BuildScanPerformanceTest
import org.gradle.testing.DistributedPerformanceTest
import org.gradle.testing.PerformanceTest
import org.gradle.testing.RebaselinePerformanceTests
import org.gradle.testing.ReportGenerationPerformanceTest
import org.gradle.testing.performance.generator.tasks.AbstractProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.JavaExecProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.JvmProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.ProjectGeneratorTask
import org.gradle.testing.performance.generator.tasks.RemoteProject
import org.gradle.testing.performance.generator.tasks.TemplateProjectGeneratorTask
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.KClass


object PropertyNames {
    const val dbUrl = "org.gradle.performance.db.url"
    const val dbUsername = "org.gradle.performance.db.username"
    const val dbPassword = "org.gradle.performance.db.password"

    const val workerTestTaskName = "org.gradle.performance.workerTestTaskName"
    const val performanceTestVerbose = "performanceTest.verbose"
    const val baselines = "org.gradle.performance.baselines"
    const val buildTypeId = "org.gradle.performance.buildTypeId"
    const val branchName = "org.gradle.performance.branchName"

    const val teamCityUsername = "teamCityUsername"
    const val teamCityPassword = "teamCityPassword"
}


val COMMIT_VERSION_REGEX = """(\d+(\.\d+)+)-commit-[a-f0-9]+""".toRegex()


private
object Config {

    val baseLineList = listOf("1.1", "1.12", "2.0", "2.1", "2.4", "2.9", "2.12", "2.14.1", "last")

    const val performanceTestScenarioListFileName = "performance-tests/scenario-list.csv"

    const val performanceTestReportsDir = "performance-tests/report"

    const val teamCityUrl = "https://builds.gradle.org/"

    const val adhocTestDbUrl = "jdbc:h2:./build/database"
}


private
const val performanceExperimentCategory = "org.gradle.performance.categories.PerformanceExperiment"


@Suppress("unused")
class PerformanceTestPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = project.run {
        apply(plugin = "java")

        val performanceTestSourceSet = createPerformanceTestSourceSet()
        addConfigurationAndDependencies()
        createCheckNoIdenticalBuildFilesTask()
        configureGeneratorTasks()

        val prepareSamplesTask = createPrepareSamplesTask()
        val configurePerformanceTestBaseline = createForkPointDistributionTask()

        createCleanSamplesTask()

        createLocalPerformanceTestTasks(performanceTestSourceSet)
        createDistributedPerformanceTestTasks(performanceTestSourceSet)

        tasks.withType<PerformanceTest>().configureEach {
            dependsOn(prepareSamplesTask, configurePerformanceTestBaseline)
        }

        createRebaselineTask(performanceTestSourceSet)

        configureIdePlugins(performanceTestSourceSet)
    }

    private
    fun Project.createForkPointDistributionTask(): TaskProvider<Task> {
        val determineForkPointCommit = tasks.register("determineForkPointCommit", DetermineForkPointCommitBaseline::class) {
            onlyIf {
                !currentBranchIsMasterOrRelease() && allPerformanceTestsHaveDefaultBaselines()
            }
        }
        val buildCommitDistribution = tasks.register("buildCommitDistribution", BuildCommitDistribution::class) {
            commitDistributionVersion.set(determineForkPointCommit.flatMap { it.forkPointCommitBaselineVersion })
            dependsOn(determineForkPointCommit)
            onlyIf {
                anyPerformanceTestHasCommitBaseline() || (!currentBranchIsMasterOrRelease() && allPerformanceTestsHaveDefaultBaselines())
            }
        }
        return tasks.register("configurePerformanceTestBaseline") {
            dependsOn(buildCommitDistribution)
            onlyIf {
                !currentBranchIsMasterOrRelease() && allPerformanceTestsHaveDefaultBaselines()
            }
            doLast {
                project.tasks.withType(PerformanceTest::class) {
                    baselines = determineForkPointCommit.get().forkPointCommitBaselineVersion.get()
                }
            }
        }
    }

    private
    fun Project.allPerformanceTestsHaveDefaultBaselines() =
        tasks.withType(PerformanceTest::class).toList().all { it.baselines.isNullOrEmpty() || it.baselines == "defaults" }

    private
    fun Project.anyPerformanceTestHasCommitBaseline() =
        tasks.withType(PerformanceTest::class).any { it.baselines?.matches(COMMIT_VERSION_REGEX) == true }

    private
    fun Project.currentBranchIsMasterOrRelease() =
        when (stringPropertyOrNull(PropertyNames.branchName)) {
            "master" -> true
            "release" -> true
            else -> false
        }

    private
    fun Project.createRebaselineTask(performanceTestSourceSet: SourceSet) {
        project.tasks.register("rebaselinePerformanceTests", RebaselinePerformanceTests::class) {
            source(performanceTestSourceSet.allSource)
        }
    }

    private
    fun Project.createPerformanceTestSourceSet(): SourceSet = java.sourceSets.run {
        val main by getting
        val test by getting
        val performanceTest by creating {
            compileClasspath += main.output + test.output
            runtimeClasspath += main.output + test.output
        }
        performanceTest
    }

    private
    fun Project.addConfigurationAndDependencies() {

        configurations {

            val testCompile by getting

            "performanceTestCompile" {
                extendsFrom(testCompile)
            }

            val testRuntime by getting

            "performanceTestRuntime" {
                extendsFrom(testRuntime)
            }

            val performanceTestRuntimeClasspath by getting

            "partialDistribution" {
                extendsFrom(performanceTestRuntimeClasspath)
            }

            create("junit")
        }

        dependencies {
            "performanceTestCompile"(project(":internalPerformanceTesting"))
            "junit"("junit:junit:4.12")
        }
    }

    private
    fun Project.createCheckNoIdenticalBuildFilesTask() {
        tasks.register("checkNoIdenticalBuildFiles") {
            doLast {
                val filesBySha1 = mutableMapOf<String, MutableList<File>>()
                buildDir.walkTopDown().forEach { file ->
                    if (file.name.endsWith(".gradle")) {
                        val sha1 = sha1StringFor(file)
                        val files = filesBySha1[sha1]
                        when (files) {
                            null -> filesBySha1[sha1] = mutableListOf(file)
                            else -> files.add(file)
                        }
                    }
                }

                filesBySha1.forEach { hash, candidates ->
                    if (candidates.size > 1) {
                        logger.lifecycle("Duplicate build files found for hash '$hash' : $candidates")
                    }
                }
            }
        }
    }

    private
    fun Project.configureGeneratorTasks() {

        tasks.withType<ProjectGeneratorTask>().configureEach {
            group = "Project setup"
        }

        tasks.withType<AbstractProjectGeneratorTask>().configureEach {
            (project.findProperty("maxProjects") as? Int)?.let { maxProjects ->
                setProjects(maxProjects)
            }
        }

        tasks.withType<JvmProjectGeneratorTask>().configureEach {
            testDependencies = configurations["junit"]
        }

        tasks.withType<TemplateProjectGeneratorTask>().configureEach {
            sharedTemplateDirectory = project(":internalPerformanceTesting").file("src/templates")
        }
    }

    private
    fun Project.createPrepareSamplesTask(): TaskProvider<Task> =
        tasks.register("prepareSamples") {
            group = "Project Setup"
            description = "Generates all sample projects for automated performance tests"
            configureSampleGenerators {
                this@register.dependsOn(this)
            }
        }

    private
    fun Project.configureSampleGenerators(action: TaskCollection<*>.() -> Unit) {
        tasks.withType<ProjectGeneratorTask>().action()
        tasks.withType<RemoteProject>().action()
        tasks.withType<JavaExecProjectGeneratorTask>().action()
    }


    private
    fun Project.createCleanSamplesTask() =
        tasks.register("cleanSamples", Delete::class) {
            configureSampleGenerators {
                this@register.delete(deferred { map { it.outputs } })
            }
        }


    private
    fun Project.createLocalPerformanceTestTasks(
        performanceSourceSet: SourceSet
    ) {

        fun create(name: String, configure: PerformanceTest.() -> Unit = {}) {
            createLocalPerformanceTestTask(name, performanceSourceSet).configure(configure)
        }

        create("performanceTest") {
            (options as JUnitOptions).excludeCategories(performanceExperimentCategory)
        }

        create("performanceExperiment") {
            (options as JUnitOptions).includeCategories(performanceExperimentCategory)
        }

        create("fullPerformanceTest")

        create("performanceAdhocTest") {
            addDatabaseParameters(mapOf(PropertyNames.dbUrl to Config.adhocTestDbUrl))
            channel = "adhoc"
            outputs.doNotCacheIf("Is adhoc performance test") { true }
        }
    }

    private
    fun Project.createDistributedPerformanceTestTasks(
        performanceSourceSet: SourceSet
    ) {

        fun create(name: String, configure: PerformanceTest.() -> Unit = {}) {
            createDistributedPerformanceTestTask(name, performanceSourceSet).configure(configure)
        }

        create("distributedPerformanceTest") {
            (options as JUnitOptions).excludeCategories(performanceExperimentCategory)
            channel = "commits"
        }
        create("distributedPerformanceExperiment") {
            (options as JUnitOptions).includeCategories(performanceExperimentCategory)
            channel = "experiments"
        }
        create("distributedFullPerformanceTest") {
            baselines = Config.baseLineList.toString()
            checks = "none"
            channel = "historical"
        }
    }

    private
    fun Project.configureIdePlugins(performanceTestSourceSet: SourceSet) {
        val performanceTestCompile by configurations
        val performanceTestRuntime by configurations
        plugins.withType<EclipsePlugin> {
            configure<EclipseModel> {
                classpath {
                    plusConfigurations.apply {
                        add(performanceTestCompile)
                        add(performanceTestRuntime)
                    }
                }
            }
        }

        plugins.withType<IdeaPlugin> {
            configure<IdeaModel> {
                module {
                    testSourceDirs = testSourceDirs + performanceTestSourceSet.groovy.srcDirs
                    testResourceDirs = testResourceDirs + performanceTestSourceSet.resources.srcDirs
                    scopes["TEST"]!!["plus"]!!.apply {
                        add(performanceTestCompile)
                        add(performanceTestRuntime)
                    }
                }
            }
        }
    }

    private
    fun Project.createDistributedPerformanceTestTask(
        name: String,
        performanceSourceSet: SourceSet
    ): TaskProvider<DistributedPerformanceTest> {

        val result = tasks.register(name, DistributedPerformanceTest::class) {
            configureForAnyPerformanceTestTask(this, performanceSourceSet)
            scenarioList = buildDir / Config.performanceTestScenarioListFileName
            buildTypeId = stringPropertyOrNull(PropertyNames.buildTypeId)
            workerTestTaskName = stringPropertyOrNull(PropertyNames.workerTestTaskName) ?: "fullPerformanceTest"
            branchName = stringPropertyOrNull(PropertyNames.branchName)
            teamCityUrl = Config.teamCityUrl
            teamCityUsername = stringPropertyOrNull(PropertyNames.teamCityUsername)
            teamCityPassword = stringPropertyOrNull(PropertyNames.teamCityPassword)
        }

        afterEvaluate {
            result.configure {
                branchName?.takeIf { it.isNotEmpty() }?.let { branchName ->
                    channel = channel + "-" + branchName
                }
            }
        }

        return result
    }

    private
    fun Project.createLocalPerformanceTestTask(
        name: String,
        performanceSourceSet: SourceSet
    ): TaskProvider<out PerformanceTest> {
        val performanceTest = tasks.register(name, determineLocalPerformanceTestClass()) {
            configureForAnyPerformanceTestTask(this, performanceSourceSet)

            if (project.hasProperty(PropertyNames.performanceTestVerbose)) {
                testLogging.showStandardStreams = true
            }
        }

        val testResultsZipTask = testResultsZipTaskFor(performanceTest, name)

        performanceTest.configure {
            finalizedBy(testResultsZipTask)
        }

        // TODO: Make this lazy, see https://github.com/gradle/gradle-native/issues/718
        tasks.getByName("clean${name.capitalize()}") {
            delete(performanceTest)
            dependsOn(testResultsZipTask.map { "clean${it.name.capitalize()}" }) // Avoid realizing because of issue
        }

        return performanceTest
    }

    private
    fun Project.determineLocalPerformanceTestClass(): KClass<out PerformanceTest> {
        return if (name == "buildScanPerformance") BuildScanPerformanceTest::class else PerformanceTest::class
    }

    private
    fun Project.testResultsZipTaskFor(performanceTest: TaskProvider<out PerformanceTest>, name: String): TaskProvider<Zip> =
        tasks.register("${name}ResultsZip", Zip::class) {
            val junitXmlDir = performanceTest.get().reports.junitXml.destination
            from(junitXmlDir) {
                include("**/TEST-*.xml")
                includeEmptyDirs = false
                eachFile {
                    try {
                        // skip files where all tests were skipped
                        if (allTestsWereSkipped(file)) {
                            exclude()
                        }
                    } catch (e: Exception) {
                        exclude()
                    }
                }
            }
            from(performanceTest.get().debugArtifactsDirectory)
            destinationDirectory.set(buildDir)
            archiveFileName.set("test-results-${junitXmlDir.name}.zip")
        }

    private
    fun Project.configureForAnyPerformanceTestTask(
        task: PerformanceTest,
        performanceSourceSet: SourceSet
    ) {

        task.apply {
            group = "verification"
            addDatabaseParameters(propertiesForPerformanceDb())
            testClassesDirs = performanceSourceSet.output.classesDirs
            classpath = performanceSourceSet.runtimeClasspath

            binaryDistributions.binZipRequired = true
            libsRepository.required = true
            maxParallelForks = 1

            project.findProperty(PropertyNames.baselines)?.let { baselines ->
                task.baselines = baselines as String
            }

            jvmArgs("-Xmx5g", "-XX:+HeapDumpOnOutOfMemoryError")

            registerTemplateInputsToPerformanceTest()

            configureSampleGenerators {
                this@apply.mustRunAfter(this)
            }
        }

        if (task is ReportGenerationPerformanceTest) {
            task.apply {
                buildId = System.getenv("BUILD_ID")
                reportDir = project.buildDir / Config.performanceTestReportsDir
            }
        }
    }

    private
    fun PerformanceTest.registerTemplateInputsToPerformanceTest() {
        val registerInputs: (Task) -> Unit = { prepareSampleTask ->
            val prepareSampleTaskInputs = prepareSampleTask.inputs.properties.mapKeys { entry -> "${prepareSampleTask.name}_${entry.key}" }
            prepareSampleTaskInputs.forEach { key, value ->
                inputs.property(key, value).optional(true)
            }
        }
        project.configureSampleGenerators {
            // TODO: Remove this hack https://github.com/gradle/gradle-native/issues/864
            (project.tasks as DefaultTaskContainer).mutationGuard.withMutationEnabled {
                all(registerInputs)
            }
        }
    }

    private
    fun Project.propertiesForPerformanceDb(): Map<String, String> =
        selectStringProperties(
            PropertyNames.dbUrl,
            PropertyNames.dbUsername,
            PropertyNames.dbPassword)
}


internal
fun allTestsWereSkipped(junitXmlFile: File): Boolean =
    parseXmlFile(junitXmlFile).documentElement.run {
        getAttribute("tests") == getAttribute("skipped")
    }


private
fun parseXmlFile(file: File): Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)


private
fun sha1StringFor(file: File) =
    HashUtil.createHash(file, "sha1").asHexString()
