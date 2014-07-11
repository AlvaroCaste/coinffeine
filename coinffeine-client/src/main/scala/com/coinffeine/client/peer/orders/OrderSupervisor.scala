package com.coinffeine.client.peer.orders

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import com.coinffeine.client.peer.CoinffeinePeerActor._
import com.coinffeine.client.peer.orders.OrderActor.RetrieveStatus
import com.coinffeine.common.{FiatAmount, Order, OrderId}
import com.coinffeine.common.exchange.PeerId
import com.coinffeine.common.protocol.ProtocolConstants

/** Manages orders */
class OrderSupervisor(orderActorProps: Props,
                      submissionSupervisorProps: Props,
                      protocolConstants: ProtocolConstants) extends Actor with ActorLogging {

  override def receive: Receive = {
    case init: OrderSupervisor.Initialize =>
      new InitializedOrdersActor(init).start()
  }

  private class InitializedOrdersActor(init: OrderSupervisor.Initialize) {

    private val submission = context.actorOf(submissionSupervisorProps)
    private var orders = Map.empty[OrderId, ActorRef]

    def start(): Unit = {
      context.become(waitingForOrders)
    }

    private val waitingForOrders: Receive = {

      case OpenOrder(order) =>
        val ref = context.actorOf(orderActorProps)
        ref ! OrderActor.Initialize(order, submission)
        orders += order.id -> ref

      case CancelOrder(order) =>
        orders.get(order.id).foreach(_ ! OrderActor.CancelOrder)

      case RetrieveOpenOrders =>
        import context.dispatcher
        implicit val timeout = Timeout(1.second)
        Future.sequence(orders.values.toSeq.map { ref =>
          (ref ? RetrieveStatus).mapTo[Order[FiatAmount]]
        }).map(RetrievedOpenOrders.apply).pipeTo(sender())
    }
  }
}

object OrderSupervisor {

  case class Initialize(ownId: PeerId,
                        brokerId: PeerId,
                        eventChannel: ActorRef,
                        gateway: ActorRef)

  trait Component { this: SubmissionSupervisor.Component
    with ProtocolConstants.Component
    with OrderActor.Component =>
    lazy val orderSupervisorProps = Props(
      new OrderSupervisor(orderActorProps, submissionSupervisorProps, protocolConstants))
  }
}
