package coinffeine.peer.market

import akka.actor._

import coinffeine.model.currency.FiatCurrency
import coinffeine.model.network.PeerId
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.market.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import coinffeine.protocol.gateway.MessageGateway.ForwardMessage
import coinffeine.protocol.messages.brokerage._

/** Submits and resubmits orders for a given market */
private[market] class MarketSubmissionActor(protocolConstants: ProtocolConstants)
  extends Actor with ActorLogging {

  override def receive: Receive = {
    case init: MarketSubmissionActor.Initialize[FiatCurrency] =>
      new InitializedOrderSubmission(init).start()
  }

  private class InitializedOrderSubmission[C <: FiatCurrency](
      init: MarketSubmissionActor.Initialize[C]) {

    import init._

    def start(): Unit = {
      context.become(waitingForOrders)
    }

    private val waitingForOrders: Receive = handleOpenOrders(PeerPositions.empty(market))

    private def keepingOpenOrders(requests: PeerPositions[FiatCurrency]): Receive =
      handleOpenOrders(requests).orElse {

        case StopSubmitting(orderId) =>
          val reducedOrderSet =
            PeerPositions(market, requests.entries.filterNot(_.id == orderId))
          forwardOrders(reducedOrderSet)

          context.become(
            if (reducedOrderSet.entries.isEmpty) waitingForOrders
            else keepingOpenOrders(reducedOrderSet)
          )

        case ReceiveTimeout =>
          forwardOrders(requests)
      }

    private def handleOpenOrders(positions: PeerPositions[FiatCurrency]): Receive = {

      case KeepSubmitting(order) =>
        val mergedOrderSet = positions.addEntry(order)
        forwardOrders(mergedOrderSet)
        context.become(keepingOpenOrders(mergedOrderSet))
    }

    private def forwardOrders(orderSet: PeerPositions[FiatCurrency]): Unit = {
      gateway ! ForwardMessage(orderSet, brokerId)
      context.setReceiveTimeout(protocolConstants.orderResubmitInterval)
    }
  }
}

private[market] object MarketSubmissionActor {

  case class Initialize[C <: FiatCurrency](market: Market[C],
                                           gateway: ActorRef,
                                           brokerId: PeerId)

  def props(constants: ProtocolConstants) = Props(new MarketSubmissionActor(constants))
}
