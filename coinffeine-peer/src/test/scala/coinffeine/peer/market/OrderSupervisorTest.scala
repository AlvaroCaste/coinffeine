package coinffeine.peer.market

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe

import coinffeine.common.akka.test.{AkkaSpec, MockSupervisedActor}
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.currency.Implicits._
import coinffeine.model.market._
import coinffeine.peer.CoinffeinePeerActor._
import coinffeine.peer.ProtocolConstants

class OrderSupervisorTest extends AkkaSpec {

  class MockOrderActor(order: Order[_ <: FiatCurrency], listener: ActorRef) extends Actor {
    override def preStart(): Unit = {
      listener ! order
    }

    override def receive: Receive = {
      case OrderActor.RetrieveStatus => sender() ! order
      case other => listener ! other
    }
  }

  object MockOrderActor {
    def props(order: Order[_ <: FiatCurrency], probe: TestProbe) =
      Props(new MockOrderActor(order, probe.ref))
  }

  "An OrderSupervisor" should "initialize the OrderSubmissionSupervisor" in new Fixture {
    givenOrderSupervisorIsInitialized()
  }

  it should "create an OrderActor when receives a create order message" in new Fixture {
    givenOrderSupervisorIsInitialized()
    givenOpenOrder(order1)
  }

  it should "cancel an order when requested" in new Fixture {
    givenOrderSupervisorIsInitialized()
    givenOpenOrder(order1)
    val reason = "foo"
    actor ! CancelOrder(order1.id, reason)
    orderActorProbe.expectMsg(OrderActor.CancelOrder(reason))
  }

  it should "collect all orders in a RetrievedOpenOrders message" in new Fixture {
    givenOrderSupervisorIsInitialized()
    givenOpenOrder(order1)
    givenOpenOrder(order2)
    actor ! RetrieveOpenOrders
    expectMsg(RetrievedOpenOrders(Seq(order1, order2)))
  }

  trait Fixture extends ProtocolConstants.DefaultComponent {
    val orderActorProbe, eventChannel, paymentProcessor, bitcoinPeer, wallet = TestProbe()
    val submissionProbe = new MockSupervisedActor()
    val actor = system.actorOf(OrderSupervisor.props(
      (order, _) => MockOrderActor.props(order, orderActorProbe), submissionProbe.props))

    val order1 = Order(Bid, 5.BTC, Price(500.EUR))
    val order2 = Order(Ask, 2.BTC, Price(800.EUR))

    def givenOrderSupervisorIsInitialized(): Unit = {
      submissionProbe.expectCreation()
    }

    def givenOpenOrder(order: Order[_ <: FiatCurrency]): Unit = {
      actor ! OpenOrder(order)
      orderActorProbe.expectMsg(order)
    }
  }
}
