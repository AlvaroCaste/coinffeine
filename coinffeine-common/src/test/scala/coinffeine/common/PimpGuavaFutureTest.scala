package coinffeine.common

import com.google.common.util.concurrent.SettableFuture

import coinffeine.common.test.UnitTest

class PimpGuavaFutureTest extends UnitTest with GuavaFutureImplicits {

  "A succeeding Guava future" should "be converted to a Scala future" in {
    val promise = SettableFuture.create[String]()
    val convertedFuture = promise.toScala
    promise.set("OK")
    convertedFuture.futureValue shouldBe "OK"
  }

  "A failing Guava future" should "be converted to a Scala future" in {
    val promise = SettableFuture.create[String]()
    val convertedFuture = promise.toScala
    val error = new Exception("Injected error")
    promise.setException(error)
    the [Exception] thrownBy convertedFuture.futureValue shouldBe error
  }
}
