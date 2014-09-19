package coinffeine.peer.api.impl

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.bitcoin.{Address, ImmutableTransaction, KeyPair}
import coinffeine.model.currency.Implicits._
import coinffeine.model.currency.{BitcoinAmount, BitcoinBalance}
import coinffeine.model.event.CoinffeineAppEvent
import coinffeine.model.properties.Property
import coinffeine.peer.api.CoinffeinePaymentProcessor.Balance
import coinffeine.peer.api._
import coinffeine.peer.api.mock.MockCoinffeineNetwork
import coinffeine.peer.payment.MockPaymentProcessorFactory

class MockCoinffeineApp extends AkkaSpec("testSystem") with CoinffeineApp {

  private var handlers: Set[EventHandler] = Set.empty

  override val network = new MockCoinffeineNetwork

  override def bitcoinNetwork = ???

  override def wallet: CoinffeineWallet = new CoinffeineWallet {
    override val balance: Property[Option[BitcoinBalance]] = null
    override val primaryAddress: Property[Option[Address]] = null
    override val transactions: Property[Seq[ImmutableTransaction]] = null
    override def transfer(amount: BitcoinAmount, address: Address) = ???
    override def importPrivateKey(address: Address, key: KeyPair) = ???
  }

  override def marketStats: MarketStats = ???

  override def paymentProcessor: CoinffeinePaymentProcessor = new CoinffeinePaymentProcessor {
    override def accountId = "fake-account-id"
    override def currentBalance() = Some(Balance(500.EUR, 10.EUR))
    override val balance = null
  }

  override def utils = ???

  override def start(timeout: FiniteDuration) = Future.successful {}
  override def stop(timeout: FiniteDuration) = Future.successful {}

  override def observe(handler: EventHandler): Unit = {
    handlers += handler
  }

  def produceEvent(event: CoinffeineAppEvent): Unit = {
    for (h <- handlers if h.isDefinedAt(event)) { h(event) }
  }
}

object MockCoinffeineApp {
  val paymentProcessorFactory = new MockPaymentProcessorFactory
}
