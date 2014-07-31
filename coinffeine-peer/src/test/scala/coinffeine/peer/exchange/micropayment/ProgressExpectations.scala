package coinffeine.peer.exchange.micropayment

import akka.testkit.TestProbe

import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.exchange.Exchange
import coinffeine.peer.exchange.ExchangeActor.ExchangeProgress

trait ProgressExpectations { this: AkkaSpec =>

  protected def listener: TestProbe
  protected def exchange: Exchange[_]

  def expectProgress(signatures: Int, payments: Int): Unit = {
    val progress = listener.expectMsgClass(classOf[ExchangeProgress]).exchange.progress
    val actualSignatures =
      progress.bitcoinsTransferred.value / exchange.amounts.stepBitcoinAmount.value
    val actualPayments = progress.fiatTransferred.value / exchange.amounts.stepFiatAmount.value
    withClue("Wrong number of signatures") {
      actualSignatures should be (signatures)
    }
    withClue("Wrong number of payments") {
      actualPayments should be(payments)
    }
  }
}
