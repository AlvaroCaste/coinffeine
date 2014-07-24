package coinffeine.peer.market

import akka.actor.{ActorLogging, Actor, ActorRef, Props}
import com.google.bitcoin.core.NetworkParameters
import com.typesafe.config.Config

import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.peer.api.event.{OrderSubmittedEvent, OrderUpdatedEvent}
import coinffeine.peer.event.EventProducer
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.market.OrderActor.{CancelOrder, Initialize, RetrieveStatus}
import coinffeine.peer.market.SubmissionSupervisor.{KeepSubmitting, StopSubmitting}
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.gateway.MessageGateway.ReceiveMessage
import coinffeine.protocol.messages.brokerage.OrderMatch

class OrderActor(exchangeActorProps: Props, network: NetworkParameters, intermediateSteps: Int)
  extends Actor with ActorLogging {

  override def receive: Receive = {
    case init: Initialize =>
      new InitializedOrderActor(init).start()
  }

  private class InitializedOrderActor(init: Initialize) extends EventProducer(init.eventChannel) {
    import init.{order => _, _}

    private val role = init.order.orderType match {
      case Bid => BuyerRole
      case Ask => SellerRole
    }
    private var currentOrder = init.order

    def start(): Unit = {
      log.info(s"Order actor initialized for ${init.order.id} using $brokerId as broker")
      messageGateway ! MessageGateway.Subscribe {
        case ReceiveMessage(orderMatch: OrderMatch, `brokerId`) =>
          orderMatch.orderId == currentOrder.id
        case _ => false
      }

      // TODO: receive a confirmation that the order was accepted in the market
      // Since the order submission cannot be confirmed, the only thing we can do with the order
      // is to set its status to `InMarketOrder` before producing the `OrderSubmittedEvent`.
      // In the future, we should receive a confirmation that the order was accepted in the market
      // and then send a `OrderUpdatedEvent` with the new status
      currentOrder = currentOrder.withStatus(InMarketOrder)
      produceEvent(OrderSubmittedEvent(currentOrder))
      init.submissionSupervisor ! KeepSubmitting(OrderBookEntry(currentOrder))
      context.become(manageOrder)
    }

    private val manageOrder: Receive = {
      case CancelOrder =>
        log.info(s"Order actor requested to cancel order ${currentOrder.id}")
        // TODO: determine the cancellation reason
        currentOrder = currentOrder.withStatus(CancelledOrder("unknown reason"))
        submissionSupervisor ! StopSubmitting(currentOrder.id)
        produceEvent(OrderUpdatedEvent(currentOrder))

      case RetrieveStatus =>
        log.debug(s"Order actor requested to retrieve status for ${currentOrder.id}")
        sender() ! currentOrder

      case ReceiveMessage(orderMatch: OrderMatch, _) =>
        log.info(s"Order actor received a match for ${currentOrder.id} " +
          s"with exchange ${orderMatch.exchangeId} and counterpart ${orderMatch.counterpart}")
        init.submissionSupervisor ! StopSubmitting(orderMatch.orderId)
        val newExchange = buildExchange(orderMatch)
        spawnExchange(newExchange)
        updateExchangeInOrder(newExchange)

      case ExchangeActor.ExchangeProgress(exchange) =>
        log.debug(s"Order actor received progress for exchange ${exchange.id}: ${exchange.progress}")
        updateExchangeInOrder(exchange)

      case ExchangeActor.ExchangeSuccess(exchange) =>
        log.debug(s"Order actor received success for exchange ${exchange.id}")
        currentOrder = currentOrder.withStatus(CompletedOrder)
        updateExchangeInOrder(exchange)
    }

    private def buildExchange(orderMatch: OrderMatch): Exchange[FiatCurrency] = {
      val fiatAmount = orderMatch.price * currentOrder.amount.value
      val amounts = Exchange.Amounts(
        currentOrder.amount, fiatAmount, Exchange.StepBreakdown(intermediateSteps))
      NonStartedExchange(
        orderMatch.exchangeId,
        amounts,
        Exchange.Parameters(orderMatch.lockTime, network),
        peerIds = null,
        brokerId
      )
    }

    private def spawnExchange(exchange: Exchange[FiatCurrency]): Unit = {
      context.actorOf(exchangeActorProps, exchange.id.value) ! ExchangeActor.StartExchange(
        exchange = exchange,
        role,
        user = Exchange.PeerInfo(null, null),
        userWallet = null,
        paymentProcessor,
        messageGateway,
        bitcoinPeer
      )
    }

    private def updateExchangeInOrder(exchange: Exchange[FiatCurrency]): Unit = {
      currentOrder = currentOrder.withExchange(exchange)
      produceEvent(OrderUpdatedEvent(currentOrder))
    }
  }
}

object OrderActor {

  case class Initialize(order: Order[FiatCurrency],
                        submissionSupervisor: ActorRef,
                        eventChannel: ActorRef,
                        messageGateway: ActorRef,
                        paymentProcessor: ActorRef,
                        bitcoinPeer: ActorRef,
                        wallet: ActorRef,
                        brokerId: PeerId)

  case object CancelOrder

  /** Ask for order status. To be replied with an [[Order]]. */
  case object RetrieveStatus

  def props(exchangeActorProps: Props, config: Config, network: NetworkParameters): Props = {
    val intermediateSteps = config.getInt("coinffeine.hardcoded.intermediateSteps")
    Props(new OrderActor(exchangeActorProps, network, intermediateSteps))
  }
}
