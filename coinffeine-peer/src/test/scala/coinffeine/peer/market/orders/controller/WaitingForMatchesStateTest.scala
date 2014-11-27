package coinffeine.peer.market.orders.controller

import org.scalatest.Inside

import coinffeine.common.test.UnitTest
import coinffeine.model.currency._
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.model.payment.OkPayPaymentProcessor
import coinffeine.peer.amounts.{AmountsCalculator, DefaultAmountsComponent}
import coinffeine.peer.exchange.protocol.MockExchangeProtocol.DummyDeposits
import coinffeine.protocol.messages.brokerage.OrderMatch

class WaitingForMatchesStateTest extends UnitTest with Inside with SampleExchange
  with DefaultAmountsComponent {

  val notStartedOrder = Order(Bid, 100.BTC, Price(1.EUR))
  val partiallyCompletedOrder = notStartedOrder
    .withExchange(buyerHandshakingExchange.startExchanging(DummyDeposits).complete)

  "When waiting for matches" should "be initially offline and trying to get to the market" in
    new FreshInstance {
      state.enter(context)
      context.updatedStatus shouldBe Some(OfflineOrder)
      context shouldBe 'askedToBeKeptInMarket
    }

  it should "reject order matches with prices off the limit" in new FreshInstance {
    val m = orderMatch(100.BTC, 2.EUR)
    state.shouldAcceptOrderMatch(context, m) shouldBe MatchRejected("Invalid price")
  }

  it should "reject order matches with amounts greater than pending" in
    new FreshInstance(partiallyCompletedOrder) {
      val m = orderMatch(95.BTC, 1.EUR)
      state.shouldAcceptOrderMatch(context, m) shouldBe MatchRejected("Invalid amount")
    }

  it should "reject order matches when amounts make no sense" in new FreshInstance {
    val m = OrderMatch(
      OrderId.random(),
      ExchangeId.random(),
      Both(buyer = 100.BTC, seller = 3.BTC),
      Both(buyer = 10.EUR, seller = 20.EUR),
      lockTime = 10,
      counterpart = PeerId.hashOf("counterpart")
    )
    state.shouldAcceptOrderMatch(context, m) shouldBe MatchRejected("Match with inconsistent amounts")
  }

  it should "accept perfect matches" in new FreshInstance {
    inside(state.shouldAcceptOrderMatch(context, orderMatch(100.BTC, 1.EUR))) {
      case MatchAccepted(_) =>
    }
  }

  it should "accept partial matches" in new FreshInstance(partiallyCompletedOrder) {
    inside(state.shouldAcceptOrderMatch(context, orderMatch(50.BTC, 1.EUR))) {
      case MatchAccepted(_) =>
    }
  }

  class StateContextMock(override val order: Order[Euro.type],
                         override val calculator: AmountsCalculator) extends StateContext[Euro.type] {

    var updatedStatus: Option[OrderStatus] = None
    var askedToBeKeptInMarket: Boolean = false
    override def updateOrderStatus(newStatus: OrderStatus): Unit = {
      updatedStatus = Some(newStatus)
    }
    override def transitionTo(state: State[Euro.type]): Unit = {}
    override def keepInMarket(): Unit = { askedToBeKeptInMarket = true }
    override def keepOffMarket(): Unit = {}
  }

  abstract class FreshInstance(val order: Order[Euro.type] = notStartedOrder) {
    val state = new WaitingForMatchesState[Euro.type]
    val context = new StateContextMock(order, amountsCalculator)

    def orderMatch(amount: Bitcoin.Amount, price: Euro.Amount) = {
      val fiatSpent = price * amount.value
      OrderMatch(
        order.id, ExchangeId.random(),
        Both(buyer = amount, seller = amount + 0.0003.BTC),
        Both(buyer = fiatSpent, seller = OkPayPaymentProcessor.amountMinusFee(fiatSpent)),
        20, PeerId.hashOf("counterpart")
      )
    }
  }
}
