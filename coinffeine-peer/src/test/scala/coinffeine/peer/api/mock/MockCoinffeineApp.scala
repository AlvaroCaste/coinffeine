package coinffeine.peer.api.mock

import coinffeine.peer.api._
import com.coinffeine.common.ProtocolConstants
import com.coinffeine.common.paymentprocessor.PaymentProcessor.Component

class MockCoinffeineApp extends CoinffeineApp {

  private var handlers: Set[EventHandler] = Set.empty

  override val network = new MockCoinffeineNetwork

  override def wallet: CoinffeineWallet = ???

  override def protocolConstants: ProtocolConstants = ???

  override def marketStats: MarketStats = ???

  override def paymentProcessors: Set[Component] = ???

  override def close(): Unit = ???

  override def observe(handler: EventHandler): Unit = {
    handlers += handler
  }

  def produceEvent(event: CoinffeineApp.Event): Unit = {
    for (h <- handlers if h.isDefinedAt(event)) { h(event) }
  }
}
