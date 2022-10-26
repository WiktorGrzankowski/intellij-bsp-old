package org.jetbrains.plugins.bsp.import

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.JavacOptionsParams
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TaskDataKind
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TestFinish
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.TestStart
import ch.epfl.scala.bsp4j.TestStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import org.jetbrains.magicmetamodel.ProjectDetails
import org.jetbrains.plugins.bsp.connection.BspServer
import org.jetbrains.plugins.bsp.services.BspRunConsoleService
import org.jetbrains.plugins.bsp.services.BspTestConsoleService
import org.jetbrains.plugins.bsp.ui.console.BspBuildConsole
import org.jetbrains.plugins.bsp.ui.console.BspSyncConsole
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture

private val importSubtaskId = "import-subtask-id"

public class VeryTemporaryBspResolver(
  private val projectBaseDir: Path,
  private val server: BspServer,
  private val bspSyncConsole: BspSyncConsole,
  private val bspBuildConsole: BspBuildConsole
) {

  public fun runTarget(targetId: BuildTargetIdentifier): RunResult {

    val uuid = "run-" + UUID.randomUUID().toString()

    val runParams = RunParams(targetId).apply {
      originId = uuid
      arguments = listOf()
    }
    return server.buildTargetRun(runParams).get()
  }

  public fun buildTargets(targetIds: List<BuildTargetIdentifier>): CompileResult {

    val uuid = "build-" + UUID.randomUUID().toString()
    val startBuildMessage: String =
      if (targetIds.size == 1) "Building ${targetIds.first().uri}"
//      else if (targetIds.isEmpty()) "?"  // consider implementing
      else "Building ${targetIds.size} target(s)"
    bspBuildConsole.startBuild(uuid, "BSP: Build", startBuildMessage)

    println("buildTargetCompile")
    val compileParams = CompileParams(targetIds).apply { originId = uuid }
    val compileResult = server.buildTargetCompile(compileParams).catchBuildErrors(uuid).get()

    when (compileResult.statusCode) {
      StatusCode.OK -> bspBuildConsole.finishBuild("Successfully completed!", uuid)
      StatusCode.CANCELLED -> bspBuildConsole.finishBuild("Cancelled!", uuid)
      StatusCode.ERROR -> bspBuildConsole.finishBuild("Ended with an error!", uuid, FailureResultImpl())
      else -> bspBuildConsole.finishBuild("Finished!", uuid)
    }

    return compileResult
  }

  public fun testTarget(targetId: BuildTargetIdentifier): TestResult {
    val params = TestParams(listOf(targetId))
    params.arguments = emptyList()
    params.originId = "test-" + UUID.randomUUID().toString()
    return server.buildTargetTest(params).get()
  }

  public fun buildTarget(targetId: BuildTargetIdentifier): CompileResult {
    return buildTargets(listOf(targetId))
  }

  public fun collectModel(): ProjectDetails {

    bspSyncConsole.startSubtask(importSubtaskId, "Collecting model...")
    println("buildInitialize")
    val initializeBuildResult = server.buildInitialize(createInitializeBuildParams()).catchSyncErrors().get()

    println("onBuildInitialized")
    server.onBuildInitialized()

    server.onBuildInitialized()
    val projectDetails = collectModelWithCapabilities(initializeBuildResult.capabilities)

    bspSyncConsole.finishSubtask(importSubtaskId, "Collection model done!")
    bspSyncConsole.finishImport("BSP: Import done!", SuccessResultImpl())

    println("done done!")
    return projectDetails
  }

  private fun collectModelWithCapabilities(buildServerCapabilities: BuildServerCapabilities): ProjectDetails {
    println("workspaceBuildTargets")
    val workspaceBuildTargetsResult = server.workspaceBuildTargets().catchSyncErrors().get()
    val allTargetsIds = workspaceBuildTargetsResult!!.targets.map(BuildTarget::getId)

    println("buildTargetSources")
    val sourcesResult = server.buildTargetSources(SourcesParams(allTargetsIds)).catchSyncErrors().get()

    println("buildTargetResources")
    val resourcesResult =
      if (buildServerCapabilities.resourcesProvider) server.buildTargetResources(ResourcesParams(allTargetsIds))
        .catchSyncErrors().get() else null

    println("buildTargetDependencySources")
    val dependencySourcesResult =
      if (buildServerCapabilities.dependencySourcesProvider) server.buildTargetDependencySources(
        DependencySourcesParams(allTargetsIds)
      ).catchSyncErrors().get() else null

    println("buildTargetJavacOptions")
    val buildTargetJavacOptionsResult =
      server.buildTargetJavacOptions(JavacOptionsParams(allTargetsIds)).catchSyncErrors().get()

    println("done done!")
    return ProjectDetails(
      targetsId = allTargetsIds,
      targets = workspaceBuildTargetsResult.targets.toSet(),
      sources = sourcesResult.items,
      resources = resourcesResult?.items ?: emptyList(),
      dependenciesSources = dependencySourcesResult?.items ?: emptyList(),
      javacOptions = buildTargetJavacOptionsResult.items
    )
  }

  private fun createInitializeBuildParams(): InitializeBuildParams {
    val params = InitializeBuildParams(
      "IntelliJ-BSP",
      "1.0.0",
      "2.0.0",
      projectBaseDir.toString(),
      BuildClientCapabilities(listOf("java"))
    )
    val dataJson = JsonObject()
    dataJson.addProperty("clientClassesRootDir", "$projectBaseDir/out")
    dataJson.add("supportedScalaVersions", JsonArray())
    params.data = dataJson

    return params
  }

  private fun <T> CompletableFuture<T>.catchSyncErrors(): CompletableFuture<T> {
    return this
      .whenComplete { _, exception ->
        exception?.let {
          bspSyncConsole.addMessage("bsp-import", "Sync failed")
          bspSyncConsole.finishImport("Failed", FailureResultImpl(exception))
        }
      }
  }

  private fun <T> CompletableFuture<T>.catchBuildErrors(buildId: String): CompletableFuture<T> {
    return this
      .whenComplete { _, exception ->
        exception?.let {
          bspBuildConsole.addMessage("bsp-build", "Build failed", buildId)
          bspBuildConsole.finishBuild("Failed", buildId, FailureResultImpl(exception))
        }
      }
  }
}

public class BspClient(
  private val bspSyncConsole: BspSyncConsole,
  private val bspBuildConsole: BspBuildConsole,
  private val bspRunConsole: BspRunConsoleService,
  private val bspTestConsole: BspTestConsoleService,
) : BuildClient {

  override fun onBuildShowMessage(params: ShowMessageParams) {
    println("onBuildShowMessage")
    println(params)
    addMessageToConsole(params.task?.id, params.message, params.originId)
  }

  override fun onBuildLogMessage(params: LogMessageParams) {
    println("onBuildLogMessage")
    println(params)
    addMessageToConsole(params.task?.id, params.message, params.originId)
  }

  override fun onBuildTaskStart(params: TaskStartParams?) {
    when (params?.dataKind) {
      TaskDataKind.TEST_START -> {
        val gson = Gson()
        val testStart = gson.fromJson(params.data as JsonObject, TestStart::class.java)
        val isSuite = params.message.take(3) == "<S>"
        println("TEST START: ${testStart?.displayName}")
        bspTestConsole.startTest(isSuite, testStart.displayName)
      }

      TaskDataKind.TEST_TASK -> {
        // ignore
      }
    }
    println("onBuildTaskStart")
    println(params)
  }

  override fun onBuildTaskProgress(params: TaskProgressParams?) {
    println("onBuildTaskProgress")
    println(params)
  }

  override fun onBuildTaskFinish(params: TaskFinishParams?) {
    when (params?.dataKind) {
      TaskDataKind.TEST_FINISH -> {
        val gson = Gson()
        val testFinish = gson.fromJson(params.data as JsonObject, TestFinish::class.java)
        val isSuite = params.message.take(3) == "<S>"
        println("TEST FINISH: ${testFinish?.displayName}")
        when (testFinish.status) {
          TestStatus.FAILED -> bspTestConsole.failTest(testFinish.displayName, testFinish.message)
          TestStatus.PASSED -> bspTestConsole.passTest(isSuite, testFinish.displayName)
          else -> bspTestConsole.ignoreTest(testFinish.displayName)
        }
      }

      TaskDataKind.TEST_REPORT -> {}
    }
    println("onBuildTaskFinish")
    println(params)
  }

  override fun onBuildPublishDiagnostics(params: PublishDiagnosticsParams) {
    println("onBuildPublishDiagnostics")
    println(params)
    addDiagnosticToConsole(params)
  }

  override fun onBuildTargetDidChange(params: DidChangeBuildTarget?) {
    println("onBuildTargetDidChange")
    println(params)
  }

  private fun addMessageToConsole(id: Any?, message: String, originId: String?) {
    if (originId?.startsWith("build") == true) {
      bspBuildConsole.addMessage(id, message, originId)
    } else if (originId?.startsWith("test") == true) {
      bspTestConsole.print(message)
    } else if (originId?.startsWith("run") == true) {
      bspRunConsole.print(message)
    } else {
      bspSyncConsole.addMessage(importSubtaskId, message)
    }
  }

  private fun addDiagnosticToConsole(params: PublishDiagnosticsParams) {
    if (params.originId?.startsWith("build") == true) {
      bspBuildConsole.addDiagnosticMessage(params)
    } else {
      bspSyncConsole.addDiagnosticMessage(params)
    }
  }
}