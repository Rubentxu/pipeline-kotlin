package dev.rubentxu.pipeline.model.validations

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ValidatorTest : FunSpec({
 context("Validator tests") {

  test("notNull should validate if object is not null") {
   val validator = "test".validate()
   validator.notNull().isValid shouldBe true
   validator.sut shouldBe "test"
  }

//  test("isNull should validate if object is null") {
//   val validator = null.validate()
//   validator.isNull().isValid shouldBe true
//   validator.sut shouldBe null
//  }

  test("notEqual should validate if object is not equal to specified value") {
   val validator = "test".validate()
   validator.notEqual("other").isValid shouldBe true
   validator.notEqual("test").isValid shouldBe false
  }

  test("equal should validate if object is equal to specified value") {
   val validator = "test".validate()
   validator.equal("test").isValid shouldBe true
   validator.equal("other").isValid shouldBe false
  }

  test("isClass should validate if object is instance of specified class") {
   val validator = "test".validate()
   validator.isClass(String::class).isValid shouldBe true
   validator.isClass(Int::class).isValid shouldBe false
  }

  test("isBoolean should validate if object is a boolean") {
   val validator = true.validate()
   validator.isBoolean().isValid shouldBe true
   validator.sut shouldBe true
  }

  test("dependsOn should validate if context contains all specified keys") {
   val validator = "test".validate()
   val context = mapOf("key1" to "value1", "key2" to "value2")
   validator.dependsOn(context, "key1", "key2").isValid shouldBe true
   validator.dependsOn(context, "key1", "key3").isValid shouldBe false
  }

  test("dependsAnyOn should validate if context contains any of the specified keys") {
   val validator = "test".validate()
   val context = mapOf("key1" to "value1", "key2" to "value2")
   validator.dependsAnyOn(context, "key1", "key3").isValid shouldBe true
   validator.dependsAnyOn(context, "key3", "key4").isValid shouldBe false
  }
 }

 test("findDeep should find a value in a nested map") {
  val cache = mutableMapOf<String, Any?>()
  val map = mapOf(
   "key1" to "value1",
   "key2" to mapOf(
    "key3" to "value3",
    "key4" to mapOf(
     "key5" to "value5"
    )
   )
  )

  map.findDeep("key1", cache) shouldBe "value1"
  map.findDeep("key2.key3", cache) shouldBe "value3"
  map.findDeep("key2.key4.key5", cache) shouldBe "value5"
  map.findDeep("key6", cache) shouldBe null
 }

 test("findDeep should find a value in a nested map with a list") {
  val cache = mutableMapOf<String, Any?>()
  val map = mapOf(
   "key1" to "value1",
   "key2" to mapOf(
    "key3" to "value3",
    "key4" to mapOf(
     "key5" to "value5"
    )
   ),
   "key6" to listOf(
    mapOf(
     "key7" to "value7"
    ),
    mapOf(
     "key8" to "value8"
    )
   )
  )

  map.findDeep("key1", cache) shouldBe "value1"
  map.findDeep("key2.key3", cache) shouldBe "value3"
  map.findDeep("key2.key4.key5", cache) shouldBe "value5"
  map.findDeep("key6[0].key7", cache) shouldBe "value7"
  map.findDeep("key6[1].key8", cache) shouldBe "value8"
  map.findDeep("key6[2].key9", cache) shouldBe null
 }
})