package org.jetbrains.workspace.model.matchers.entries

import com.intellij.workspaceModel.storage.bridgeEntities.ContentRootEntity
import com.intellij.workspaceModel.storage.bridgeEntities.ModuleEntity
import com.intellij.workspaceModel.storage.url.VirtualFileUrl
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.workspace.model.matchers.shouldContainExactlyInAnyOrder

public data class ExpectedContentRootEntity(
  val url: VirtualFileUrl,
  val excludedUrls: List<VirtualFileUrl>,
  val excludedPatterns: List<String>,
  val parentModuleEntity: ModuleEntity,
)

public infix fun ContentRootEntity.shouldBeEqual(expected: ExpectedContentRootEntity): Unit =
  validateContentRootEntity(this, expected)

public infix fun Collection<ContentRootEntity>.shouldContainExactlyInAnyOrder(
  expectedValues: Collection<ExpectedContentRootEntity>,
): Unit =
  this.shouldContainExactlyInAnyOrder(::validateContentRootEntity, expectedValues)

private fun validateContentRootEntity(
  actual: ContentRootEntity,
  expected: ExpectedContentRootEntity
) {
  actual.url shouldBe expected.url
  actual.excludedUrls.map { it.url } shouldContainExactlyInAnyOrder expected.excludedUrls
  actual.excludedPatterns shouldContainExactlyInAnyOrder expected.excludedPatterns
}
