package coinffeine.peer.market

import akka.actor.Props
import akka.testkit.TestProbe

import coinffeine.common.test.{AkkaSpec, MockSupervisedActor}
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.{Exchange, Both, CompletedExchange, ExchangeId}
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.peer.api.event.{OrderSubmittedEvent, OrderUpdatedEvent}
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.market.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import coinffeine.protocol.gateway.GatewayProbe
import coinffeine.protocol.messages.brokerage.OrderMatch

class OrderActorTest extends AkkaSpec {

  "An order actor" should "keep order info" in new Fixture {
    actor ! OrderActor.RetrieveStatus
    expectMsg(inMarketOrder)
  }

  it should "keep submitting to the broker until been cancelled" in new Fixture {
    submissionProbe.expectMsg(KeepSubmitting(OrderBookEntry(order)))
    expectNoMsg()
    actor ! OrderActor.CancelOrder
    submissionProbe.expectMsg(StopSubmitting(order.id))
  }

  it should "notify order creation and cancellation" in new Fixture {
    eventChannelProbe.expectMsg(OrderSubmittedEvent(inMarketOrder))
    actor ! OrderActor.CancelOrder
    eventChannelProbe.expectMsgPF() {
      case OrderUpdatedEvent(Order(_, _, CancelledOrder(_), _, _, _)) =>
    }
  }

  it should "stop submitting to the broker & send event once matching is received" in new Fixture {
    val exchangeId = ExchangeId.random()
    val counterpart = PeerId("counterpart")
    val orderMatch = OrderMatch(
      order.id, exchangeId, order.amount, order.price, lockTime = 400000L, counterpart)
    gatewayProbe.relayMessage(orderMatch, brokerId)
    submissionProbe.fishForMessage() {
      case StopSubmitting(order.`id`) => true
      case _ => false
    }
    exchange.probe.send(actor, ExchangeActor.ExchangeSuccess(CompletedExchange(
      id = exchangeId,
      peerIds = Both(counterpart, PeerId("me")),
      parameters = Exchange.Parameters(10, network),
      brokerId,
      amounts = Exchange.Amounts[FiatCurrency](
        order.amount, order.price * order.amount.value, Exchange.StepBreakdown(10))
    )))
    eventChannelProbe.fishForMessage() {
      case OrderUpdatedEvent(Order(_, _, CompletedOrder, _, _, _)) => true
      case _ => false
    }
  }

  trait Fixture extends CoinffeineUnitTestNetwork.Component {
    val gatewayProbe = new GatewayProbe()
    val submissionProbe, eventChannelProbe, paymentProcessorProbe, bitcoinPeerProbe, walletProbe = TestProbe()
    val brokerId = PeerId("broker")
    val exchange = new MockSupervisedActor()
    val actor = system.actorOf(Props(new OrderActor(exchange.props, network, 10)))
    val order = Order(Ask, 5.BTC, 500.EUR)
    val inMarketOrder = order.withStatus(InMarketOrder)

    actor ! OrderActor.Initialize(order, submissionProbe.ref, eventChannelProbe.ref,
      gatewayProbe.ref, paymentProcessorProbe.ref, bitcoinPeerProbe.ref, walletProbe.ref, brokerId)
    gatewayProbe.expectSubscription()
  }
}
