package coinffeine.peer.market.orders

import scala.util.{Failure, Success}

import akka.actor._
import akka.event.Logging
import akka.persistence.{RecoveryCompleted, PersistentActor}
import org.bitcoinj.core.NetworkParameters

import coinffeine.common.akka.persistence.PersistentEvent
import coinffeine.model.currency._
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.{BrokerId, MutableCoinffeineNetworkProperties}
import coinffeine.peer.amounts.AmountsCalculator
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.market.orders.controller._
import coinffeine.peer.market.orders.funds.FundsBlockerActor
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.gateway.MessageGateway.{ForwardMessage, ReceiveMessage}
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake.ExchangeRejection

class OrderActor[C <: FiatCurrency](
    initialOrder: Order[C],
    order: OrderController[C],
    delegates: OrderActor.Delegates[C],
    coinffeineProperties: MutableCoinffeineNetworkProperties,
    collaborators: OrderActor.Collaborators)
  extends PersistentActor with ActorLogging with OrderPublisher.Listener {

  import OrderActor._

  private case class BlockingInProgress(orderMatch: OrderMatch[C], funds: RequiredFunds[C])

  private val orderId = initialOrder.id
  override val persistenceId: String = s"order-${orderId.value}"
  private val currency = initialOrder.price.currency
  private val publisher = new OrderPublisher[C](collaborators.submissionSupervisor, this)
  private var blockingInProgress: Option[BlockingInProgress] = None
  private var started = false

  override def preStart(): Unit = {
    log.info("Order actor initialized for {}", orderId)
    subscribeToOrderMatches()
    subscribeToOrderChanges()
    super.preStart()
  }

  override def receiveRecover: Receive = {
    case OrderStarted => onOrderStarted()
    case event: AcceptedOrderMatch[_] =>
      onAcceptedOrderMatch(event.asInstanceOf[AcceptedOrderMatch[C]])
    case FundsBlocked => onFundsBlocked()
    case CannotBlockFunds => onCannotBlockFunds()
    case event: CancelledOrder => onCancelledOrder(event)
    case RecoveryCompleted => self ! ResumeOrder
  }

  private def onOrderStarted(): Unit = {
    started = true
  }

  private def onAcceptedOrderMatch(event: AcceptedOrderMatch[C]): Unit = {
    blockingInProgress = Some(BlockingInProgress(event.orderMatch, event.requiredFunds))
  }

  private def onFundsBlocked(): Unit = {
    val exchange = order.acceptOrderMatch(blockingInProgress.get.orderMatch)
    blockingInProgress = None
    context.actorOf(delegates.exchangeActor(exchange), exchange.id.value)
  }

  private def onCannotBlockFunds(): Unit = {
    blockingInProgress = None
  }

  private def onCancelledOrder(event: CancelledOrder): Unit = {
    order.cancel(event.reason)
  }

  override def receiveCommand = publisher.receiveSubmissionEvents orElse {
    case ResumeOrder => resumeOrder()

    case ReceiveMessage(orderMatch: OrderMatch[_], _) if blockingInProgress.nonEmpty =>
      rejectOrderMatch("Accepting other match", orderMatch)

    case ReceiveMessage(message: OrderMatch[_], _) if message.currency == currency =>
      val orderMatch = message.asInstanceOf[OrderMatch[C]]
      order.shouldAcceptOrderMatch(orderMatch) match {
        case MatchAccepted(requiredFunds) =>
          persist(AcceptedOrderMatch(orderMatch, requiredFunds)) { event =>
            log.info("Blocking funds for {}", orderMatch)
            onAcceptedOrderMatch(event)
            requestPendingFunds()
          }
        case MatchRejected(cause) =>
          rejectOrderMatch(cause, orderMatch)
        case MatchAlreadyAccepted(oldExchange) =>
          log.debug("Received order match for the already accepted exchange {}", oldExchange.id)
      }

    case FundsBlockerActor.BlockingResult(result) if blockingInProgress.isEmpty =>
      log.warning("Unexpected blocking result {}", result)

    case FundsBlockerActor.BlockingResult(Success(_)) =>
      val orderMatch = blockingInProgress.get.orderMatch
      persist(FundsBlocked) { _ =>
        log.info("Accepting match for {} against counterpart {} identified as {}",
          orderId, orderMatch.counterpart, orderMatch.exchangeId)
        onFundsBlocked()
      }

    case FundsBlockerActor.BlockingResult(Failure(cause)) =>
      persist(CannotBlockFunds) { _ =>
        log.error(cause, "Cannot block funds")
        rejectOrderMatch("Cannot block funds", blockingInProgress.get.orderMatch)
        onCannotBlockFunds()
      }

    case CancelOrder(reason) =>
      log.info("Cancelling order {}", orderId)
      persist(CancelledOrder(reason))(onCancelledOrder)

    case ExchangeActor.ExchangeUpdate(exchange) if exchange.currency == currency =>
      log.debug("Order actor received update for {}: {}", exchange.id, exchange.progress)
      order.updateExchange(exchange.asInstanceOf[AnyStateExchange[C]])

    case ExchangeActor.ExchangeSuccess(exchange) if exchange.currency == currency =>
      completeExchange(exchange.asInstanceOf[SuccessfulExchange[C]])

    case ExchangeActor.ExchangeFailure(exchange) if exchange.currency == currency =>
      completeExchange(exchange.asInstanceOf[FailedExchange[C]])
  }

  private def resumeOrder(): Unit = {
    requestPendingFunds()
    val currentOrder = order.view
    if (order.shouldBeOnMarket) {
      publisher.keepPublishing(currentOrder.pendingOrderBookEntry)
    }
    coinffeineProperties.orders.set(currentOrder.id, currentOrder)
    if (!started) {
      persist(OrderStarted) { _ =>
        coinffeineProperties.orders.set(orderId, initialOrder)
        onOrderStarted()
      }
    }
  }

  override def inMarket(): Unit = { order.becomeInMarket() }
  override def offline(): Unit = { order.becomeOffline() }

  private def requestPendingFunds(): Unit = {
    blockingInProgress.foreach { blocking =>
      context.actorOf(delegates.fundsBlocker(blocking.orderMatch.exchangeId, blocking.funds))
    }
  }

  private def subscribeToOrderMatches(): Unit = {
    collaborators.gateway ! MessageGateway.Subscribe.fromBroker {
      case orderMatch: OrderMatch[_] if orderMatch.orderId == orderId &&
        orderMatch.currency == currency =>
    }
  }

  private def subscribeToOrderChanges(): Unit = {
    order.addListener(new OrderController.Listener[C] {
      override def onOrderChange(oldOrder: Order[C], newOrder: Order[C]): Unit = {
        if (recoveryFinished) {
          if (newOrder.status != oldOrder.status) {
            log.info("Order {} has now {} status", orderId, newOrder.status)
          }
          if (newOrder.progress != oldOrder.progress) {
            log.debug("Order {} progress: {}%", orderId, (100 * newOrder.progress).formatted("%5.2f"))
          }
          coinffeineProperties.orders.set(newOrder.id, newOrder)
        }
      }

      override def keepInMarket(): Unit = {
        if (recoveryFinished) {
          publisher.keepPublishing(order.view.pendingOrderBookEntry)
        }
      }

      override def keepOffMarket(): Unit = {
        if (recoveryFinished) {
          publisher.stopPublishing()
        }
      }
    })
  }

  private def rejectOrderMatch(cause: String, rejectedMatch: OrderMatch[_]): Unit = {
    log.info("Rejecting match for {} against counterpart {}: {}",
      orderId, rejectedMatch.counterpart, cause)
    val rejection = ExchangeRejection(rejectedMatch.exchangeId, cause)
    collaborators.gateway ! ForwardMessage(rejection, BrokerId)
  }

  private def completeExchange(exchange: CompletedExchange[C]): Unit = {
    val level = if (exchange.state.isSuccess) Logging.InfoLevel else Logging.ErrorLevel
    log.log(level, "Exchange {}: completed with state {}", exchange.id, exchange.state)
    order.completeExchange(exchange)
  }
}

object OrderActor {
  case class Collaborators(wallet: ActorRef,
                           paymentProcessor: ActorRef,
                           submissionSupervisor: ActorRef,
                           gateway: ActorRef,
                           bitcoinPeer: ActorRef,
                           blockchain: ActorRef)

  trait Delegates[C <: FiatCurrency] {
    def exchangeActor(exchange: NonStartedExchange[C])(implicit context: ActorContext): Props
    def fundsBlocker(id: ExchangeId, funds: RequiredFunds[C])(implicit context: ActorContext): Props
  }

  case class CancelOrder(reason: String)

  def props[C <: FiatCurrency](exchangeActorProps: (NonStartedExchange[C], ExchangeActor.Collaborators) => Props,
                               network: NetworkParameters,
                               amountsCalculator: AmountsCalculator,
                               order: Order[C],
                               coinffeineProperties: MutableCoinffeineNetworkProperties,
                               collaborators: Collaborators): Props = {
    import collaborators._
    val delegates = new Delegates[C] {
      override def exchangeActor(exchange: NonStartedExchange[C])(implicit context: ActorContext) = {
        exchangeActorProps(exchange, ExchangeActor.Collaborators(
          wallet, paymentProcessor, gateway, bitcoinPeer, blockchain, context.self))
      }
      override def fundsBlocker(id: ExchangeId, funds: RequiredFunds[C])
                               (implicit context: ActorContext) =
        FundsBlockerActor.props(id, wallet, paymentProcessor, funds, context.self)
    }
    Props(new OrderActor[C](
      order,
      new OrderController(amountsCalculator, network, order),
      delegates,
      coinffeineProperties,
      collaborators
    ))
  }

  private case object ResumeOrder
  private case object OrderStarted extends PersistentEvent
  private case class AcceptedOrderMatch[C <: FiatCurrency](
      orderMatch: OrderMatch[C], requiredFunds: RequiredFunds[C]) extends PersistentEvent
  private case object FundsBlocked extends PersistentEvent
  private case object CannotBlockFunds extends PersistentEvent
  private case class CancelledOrder(reason: String) extends PersistentEvent
}
