package org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.transformers

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetDataKind
import ch.epfl.scala.bsp4j.JvmBuildTarget
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.jetbrains.magicmetamodel.impl.workspacemodel.ModuleDetails
import org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.JavaModule
import org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.JvmJdkInfo
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.toPath

internal object ModuleDetailsToJavaModuleTransformer : ModuleDetailsToModuleTransformer<JavaModule>() {

  override val type = "JAVA_MODULE"

  override fun transform(inputEntity: ModuleDetails): JavaModule =
    JavaModule(
      module = toModule(inputEntity, includeJavacOpts = true, includePythonOpts = false),
      baseDirContentRoot = toBaseDirContentRoot(inputEntity),
      sourceRoots = SourcesItemToJavaSourceRootTransformer.transform(inputEntity.sources.map {
        BuildTargetAndSourceItem(
          inputEntity.target,
          it,
        )
      }),
      resourceRoots = ResourcesItemToJavaResourceRootTransformer.transform(inputEntity.resources),
      libraries = DependencySourcesItemToLibraryTransformer.transform(inputEntity.dependenciesSources.map {
        DependencySourcesAndJavacOptions(
          it,
          inputEntity.javacOptions
        )
      }),
      compilerOutput = toCompilerOutput(inputEntity),
      jvmJdkInfo = toJdkInfo(inputEntity)
    )

  private fun toCompilerOutput(inputEntity: ModuleDetails): Path? =
    inputEntity.javacOptions?.classDirectory?.let { URI(it).toPath() }

  private fun toJdkInfo(inputEntity: ModuleDetails): JvmJdkInfo? {
    val jvmBuildTarget = extractJvmBuildTarget(inputEntity.target)
    return if (jvmBuildTarget != null) JvmJdkInfo(
      javaVersion = jvmBuildTarget.javaVersion,
      javaHome = jvmBuildTarget.javaHome
    )
    else null
  }

}

public fun extractJvmBuildTarget(target: BuildTarget): JvmBuildTarget? =
  if (target.dataKind == BuildTargetDataKind.JVM) Gson().fromJson(target.data as JsonObject, JvmBuildTarget::class.java)
  else null
