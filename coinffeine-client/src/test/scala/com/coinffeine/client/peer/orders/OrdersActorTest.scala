package com.coinffeine.client.peer.orders

import scala.concurrent.duration._

import akka.actor.Props

import com.coinffeine.client.peer.CoinffeinePeerActor.{CancelOrder, OpenOrder, RetrieveOpenOrders, RetrievedOpenOrders}
import com.coinffeine.common._
import com.coinffeine.common.Currency.{Euro, UsDollar}
import com.coinffeine.common.Currency.Implicits._
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.GatewayProbe
import com.coinffeine.common.protocol.messages.brokerage.{Market, OrderSet}
import com.coinffeine.common.test.AkkaSpec

class OrdersActorTest extends AkkaSpec {

  val constants = ProtocolConstants.DefaultConstants.copy(
    orderExpirationInterval = 6.seconds,
    orderResubmitInterval = 4.seconds
  )
  val address = PeerConnection("peer")
  val broker = PeerConnection("broker")
  val eurOrder1 = Order(Bid, 1.3.BTC, 556.EUR)
  val eurOrder2 = Order(Ask, 0.7.BTC, 640.EUR)
  val usdOrder = Order(Ask, 0.5.BTC, 500.USD)
  val noEurOrders = OrderSet.empty(Market(Euro))
  val firstEurOrder = noEurOrders.addOrder(Bid, 1.3.BTC, 556.EUR)
  val bothEurOrders = firstEurOrder.addOrder(Ask, 0.7.BTC, 640.EUR)

  trait Fixture {
    val gateway = new GatewayProbe()
    val actor = system.actorOf(Props(new OrdersActor(constants)))
    actor ! OrdersActor.Initialize(address, broker, gateway.ref)
  }

  "An order submission actor" must "keep silent as long as there is no open orders" in new Fixture {
    gateway.expectNoMsg()
  }

  it must "submit all orders as soon as a new one is open" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    gateway.expectForwarding(firstEurOrder, broker)
    actor ! OpenOrder(eurOrder2)
    gateway.expectForwarding(bothEurOrders, broker)
  }

  it must "keep resubmitting open orders to avoid them being discarded" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    gateway.expectForwarding(firstEurOrder, broker)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
  }

  it must "group orders by target market" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    actor ! OpenOrder(usdOrder)

    def currencyOfNextOrderSet(): FiatCurrency =
      gateway.expectForwardingPF(broker, constants.orderExpirationInterval) {
        case OrderSet(Market(currency), _, _) => currency
      }

    val currencies = Set(currencyOfNextOrderSet(), currencyOfNextOrderSet())
    currencies should be (Set(Euro, UsDollar))
  }

  it must "keep resubmitting remaining orders after a cancellation" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    gateway.expectForwarding(firstEurOrder, broker)
    actor ! OpenOrder(eurOrder2)
    gateway.expectForwarding(bothEurOrders, broker)
    actor ! CancelOrder(eurOrder2)
    gateway.expectForwarding(firstEurOrder, broker)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
  }

  it must "keep silent if all the orders get cancelled" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    gateway.expectForwarding(firstEurOrder, broker)
    actor ! CancelOrder(eurOrder1)
    gateway.expectForwarding(noEurOrders, broker)
    gateway.expectNoMsg(constants.orderExpirationInterval)
  }

  it must "retrieve an empty OpenOrders when there is no open orders" in new Fixture {
    actor ! RetrieveOpenOrders
    expectMsg(RetrievedOpenOrders(Set.empty))
  }

  it must "retrieve open orders" in new Fixture {
    actor ! OpenOrder(eurOrder1)
    actor ! OpenOrder(usdOrder)
    val eurMarket = Market(Euro)
    val usdMarket = Market(UsDollar)
    actor ! RetrieveOpenOrders
    expectMsg(RetrievedOpenOrders(Set(eurOrder1, usdOrder)))
  }
}
