package coinffeine.peer.market

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe

import coinffeine.model.currency.FiatAmount
import coinffeine.model.currency.Implicits._
import coinffeine.model.market.{Ask, Bid, OrderBookEntry}
import coinffeine.model.network.PeerId
import coinffeine.peer.CoinffeinePeerActor._
import com.coinffeine.common.ProtocolConstants
import com.coinffeine.common.test.MockActor.{MockReceived, MockStarted}
import com.coinffeine.common.test.{AkkaSpec, MockActor}

class OrderSupervisorTest extends AkkaSpec {

  class MockOrderActor(listener: ActorRef) extends Actor {
    private var order: OrderBookEntry[FiatAmount] = _

    override def receive: Receive = {
      case init: OrderActor.Initialize =>
        order = init.order
        listener ! init
      case OrderActor.RetrieveStatus =>
        sender() ! order
      case other =>
        listener ! other
    }
  }

  object MockOrderActor {
    def props(probe: TestProbe) = Props(new MockOrderActor(probe.ref))
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
    actor ! CancelOrder(order1.id)
    orderActorProbe.expectMsg(OrderActor.CancelOrder)
  }

  it should "collect all orders in a RetrievedOpenOrders message" in new Fixture {
    givenOrderSupervisorIsInitialized()
    givenOpenOrder(order1)
    givenOpenOrder(order2)
    actor ! RetrieveOpenOrders
    expectMsg(RetrievedOpenOrders(Seq(order1, order2)))
  }

  trait Fixture extends ProtocolConstants.DefaultComponent {
    val orderActorProbe = TestProbe()
    val submissionProbe = TestProbe()
    val eventChannel = TestProbe()
    val gateway = TestProbe()
    val actor = system.actorOf(Props(new OrderSupervisor(MockOrderActor.props(orderActorProbe),
      MockActor.props(submissionProbe), protocolConstants)))

    val order1 = OrderBookEntry(Bid, 5.BTC, 500.EUR)
    val order2 = OrderBookEntry(Ask, 2.BTC, 800.EUR)

    def givenOrderSupervisorIsInitialized(): Unit = {
      val brokerId = PeerId("Broker")
      val initMessage = OrderSupervisor.Initialize(brokerId, eventChannel.ref, gateway.ref)
      actor ! initMessage
      submissionProbe.expectMsgClass(classOf[MockStarted])
      val gatewayRef = gateway.ref
      submissionProbe.expectMsgPF() {
        case MockReceived(_, _, SubmissionSupervisor.Initialize(`brokerId`, `gatewayRef`)) =>
      }
    }

    def givenOpenOrder(order: OrderBookEntry[FiatAmount]): Unit = {
      actor ! OpenOrder(order)
      orderActorProbe.expectMsgPF() {
        case OrderActor.Initialize(`order`, _, _, _) =>
      }
    }
  }
}
