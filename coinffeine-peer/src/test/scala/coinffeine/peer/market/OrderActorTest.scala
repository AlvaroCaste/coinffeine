package coinffeine.peer.market

import akka.actor.Props
import akka.testkit.TestProbe

import coinffeine.common.test.AkkaSpec
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.ExchangeId
import coinffeine.model.market.{Ask, OrderBookEntry}
import coinffeine.model.network.PeerId
import coinffeine.peer.api.CoinffeineApp
import coinffeine.peer.market.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import coinffeine.protocol.messages.brokerage.OrderMatch

class OrderActorTest extends AkkaSpec {

  "An order actor" should "keep order info" in new Fixture {
    actor ! OrderActor.RetrieveStatus
    expectMsg(order)
  }

  it should "keep submitting to the broker until been cancelled" in new Fixture {
    submissionProbe.expectMsg(KeepSubmitting(order))
    expectNoMsg()
    actor ! OrderActor.CancelOrder
    submissionProbe.expectMsg(StopSubmitting(order.id))
  }

  it should "notify order creation and cancellation" in new Fixture {
    eventChannelProbe.expectMsg(CoinffeineApp.OrderSubmittedEvent(order))
    actor ! OrderActor.CancelOrder
    eventChannelProbe.expectMsg(CoinffeineApp.OrderCancelledEvent(order.id))
  }

  it should "stop submitting to the broker & send event once matching is received" in new Fixture {
    actor ! OrderMatch(
      order.id, ExchangeId.random(), order.amount, order.price, PeerId.apply("counterpart"))
    submissionProbe.fishForMessage() {
      case StopSubmitting(order.`id`) => true
      case _ => false
    }
    eventChannelProbe.fishForMessage() {
      case CoinffeineApp.OrderCancelledEvent(order.id) => true
      case _ => false
    }
  }

  trait Fixture {
    val messageGatewayProbe = TestProbe()
    val eventChannelProbe = TestProbe()
    val actor = system.actorOf(Props(new OrderActor))
    val order = OrderBookEntry(Ask, 5.BTC, 500.EUR)
    val submissionProbe = TestProbe()
    val paymentProcessorProbe = TestProbe()
    val walletProbe = TestProbe()

    actor ! OrderActor.Initialize(order, submissionProbe.ref, eventChannelProbe.ref,
      messageGatewayProbe.ref, paymentProcessorProbe.ref, walletProbe.ref)
  }
}
