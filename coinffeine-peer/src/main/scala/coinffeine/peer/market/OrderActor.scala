package coinffeine.peer.market

import akka.actor.{Actor, ActorRef, Props}

import coinffeine.model.currency.{FiatCurrency, FiatAmount}
import coinffeine.model.market.{CancelledOrder, Order, OrderBookEntry}
import coinffeine.peer.api.event.{OrderCancelledEvent, OrderSubmittedEvent}
import coinffeine.peer.event.EventProducer
import coinffeine.peer.market.OrderActor.{CancelOrder, Initialize, RetrieveStatus}
import coinffeine.peer.market.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.messages.brokerage.OrderMatch

class OrderActor extends Actor {

  override def receive: Receive = {
    case init: Initialize =>
      new InitializedOrderActor(init).start()
  }

  private class InitializedOrderActor(init: Initialize) extends EventProducer(init.eventChannel) {
    import init._

    var currentOrder = init.order

    def start(): Unit = {
      messageGateway ! MessageGateway.Subscribe {
        case o: OrderMatch if o.orderId == order.id => true
        case _ => false
      }
      produceEvent(OrderSubmittedEvent(order))
      init.submissionSupervisor ! KeepSubmitting(OrderBookEntry(init.order))
      context.become(manageOrder)
    }

    private val manageOrder: Receive = {
      case CancelOrder =>
        // TODO: determine the cancellation reason
        currentOrder = currentOrder.withStatus(CancelledOrder("unknown reason"))
        submissionSupervisor ! StopSubmitting(currentOrder.id)
        produceEvent(OrderCancelledEvent(currentOrder.id))

      case RetrieveStatus =>
        sender() ! currentOrder

      case orderMatch: OrderMatch =>
        init.submissionSupervisor ! StopSubmitting(orderMatch.orderId)
        // TODO: create the exchange, update currentOrder and send an OrderUpdatedEvent
        produceEvent(OrderCancelledEvent(orderMatch.orderId))
    }
  }
}

object OrderActor {

  case class Initialize(order: Order[FiatCurrency],
                        submissionSupervisor: ActorRef,
                        eventChannel: ActorRef,
                        messageGateway: ActorRef,
                        paymentProcessor: ActorRef,
                        wallet: ActorRef)

  case object CancelOrder

  /** Ask for order status. To be replied with an [[Order]]. */
  case object RetrieveStatus

  trait Component {
    lazy val orderActorProps: Props = Props(new OrderActor)
  }
}
