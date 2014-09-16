package coinffeine.peer

import scala.concurrent.duration._

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import coinffeine.common.akka.{AskPattern, ServiceActor}
import coinffeine.model.bitcoin.NetworkComponent
import coinffeine.model.currency.{BitcoinAmount, FiatCurrency}
import coinffeine.model.market.{Order, OrderId}
import coinffeine.peer.amounts.AmountsComponent
import coinffeine.peer.bitcoin.BitcoinPeerActor
import coinffeine.peer.config.ConfigComponent
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.market._
import coinffeine.peer.market.orders.{OrderSupervisor, OrderActor}
import coinffeine.peer.payment.PaymentProcessorActor.RetrieveBalance
import coinffeine.peer.payment.okpay.OkPayProcessorActor
import coinffeine.protocol.gateway.MessageGateway
import coinffeine.protocol.gateway.MessageGateway.BrokerAddress
import coinffeine.protocol.messages.brokerage
import coinffeine.protocol.messages.brokerage.{OpenOrdersRequest, QuoteRequest}

/** Implementation of the topmost actor on a peer node. It starts all the relevant actors like
  * the peer actor and the message gateway and supervise them.
  */
class CoinffeinePeerActor(
    listenPort: Int,
    brokerAddress: BrokerAddress,
    props: CoinffeinePeerActor.PropsCatalogue) extends Actor with ActorLogging with ServiceActor[Unit] {
  import context.dispatcher

import coinffeine.peer.CoinffeinePeerActor._

  private val gatewayRef = context.actorOf(props.gateway, "gateway")
  private val paymentProcessorRef = context.actorOf(props.paymentProcessor, "paymentProcessor")
  private val bitcoinPeerRef = context.actorOf(props.bitcoinPeer, "bitcoinPeer")
  private val marketInfoRef = context.actorOf(props.marketInfo(gatewayRef), "marketInfo")
  private var orderSupervisorRef: ActorRef = _
  private var walletRef: ActorRef = _

  override def starting(args: Unit) = {
    implicit val timeout = Timeout(ServiceStartStopTimeout)
    log.info("Starting Coinffeine peer actor...")
    // TODO: replace all children actors by services and start them here
    (for {
      _ <- ServiceActor.askStart(paymentProcessorRef)
      _ <- ServiceActor.askStart(bitcoinPeerRef)
      _ <- ServiceActor.askStart(gatewayRef, MessageGateway.JoinAsPeer(listenPort, brokerAddress))
      walletActorRef <- AskPattern(bitcoinPeerRef, BitcoinPeerActor.RetrieveWalletActor)
        .withReply[BitcoinPeerActor.WalletActorRef]
    } yield walletActorRef).pipeTo(self)

    handle {
      case BitcoinPeerActor.WalletActorRef(retrievedWalletRef) =>
        walletRef = retrievedWalletRef
        val collaborators = OrderSupervisorCollaborators(
          gatewayRef, paymentProcessorRef, bitcoinPeerRef, walletRef)
        orderSupervisorRef = context.actorOf(props.orderSupervisor(collaborators), "orders")
        becomeStarted(handleMessages)
        log.info("Coinffeine peer actor successfully started!")
      case Status.Failure(cause) =>
        log.error(cause, "Coinffeine peer actor failed to start")
        cancelStart(cause)
    }
  }

  override protected def stopping(): Receive = {
    implicit val timeout = Timeout(ServiceStartStopTimeout)
    ServiceActor.askStopAll(paymentProcessorRef, bitcoinPeerRef, gatewayRef).pipeTo(self)
    handle {
      case () => becomeStopped()
      case Status.Failure(cause) => cancelStop(cause)
    }
  }

  private val handleMessages: Receive = {
    case message @ (OpenOrder(_) | CancelOrder(_, _) | RetrieveOpenOrders) =>
      orderSupervisorRef forward message
    case message @ RetrieveBalance(_) =>
      paymentProcessorRef forward message

    case QuoteRequest(market) =>
      marketInfoRef.forward(MarketInfoActor.RequestQuote(market))
    case OpenOrdersRequest(market) =>
      marketInfoRef.forward(MarketInfoActor.RequestOpenOrders(market))
  }
}

/** Topmost actor on a peer node. */
object CoinffeinePeerActor {

  val ServiceStartStopTimeout = 10.seconds

  /** Open a new order.
    *
    * Note that, in case of having a previous order at the same price, this means an increment
    * of its amount.
    *
    * @param order Order to open
    */
  case class OpenOrder(order: Order[_ <: FiatCurrency])

  /** Cancel an order
    *
    * Note that this can cancel partially an existing order for a greater amount of bitcoin.
    *
    * @param order  Order to cancel
    * @param reason A user friendly description of why the order is cancelled
    */
  case class CancelOrder(order: OrderId, reason: String)

  /** Ask for own orders opened in any market. */
  case object RetrieveOpenOrders

  /** Reply to [[RetrieveOpenOrders]] message. */
  case class RetrievedOpenOrders(orders: Seq[Order[_ <: FiatCurrency]])

  /** Ask for the currently open orders. To be replied with an [[brokerage.OpenOrders]]. */
  type RetrieveMarketOrders = brokerage.OpenOrdersRequest

  case class OrderSupervisorCollaborators(gateway: ActorRef,
                                          paymentProcessor: ActorRef,
                                          bitcoinPeer: ActorRef,
                                          wallet: ActorRef)

  case class PropsCatalogue(gateway: Props,
                            marketInfo: ActorRef => Props,
                            orderSupervisor: OrderSupervisorCollaborators => Props,
                            bitcoinPeer: Props,
                            paymentProcessor: Props)

  trait Component { this: MessageGateway.Component
    with BitcoinPeerActor.Component
    with ExchangeActor.Component
    with ConfigComponent
    with NetworkComponent
    with ProtocolConstants.Component
    with AmountsComponent =>

    lazy val peerProps: Props = {
      val ownPort = configProvider.messageGatewaySettings.peerPort
      val brokerHostname = configProvider.messageGatewaySettings.brokerHost
      val brokerPort= configProvider.messageGatewaySettings.brokerPort
      val props = PropsCatalogue(
        messageGatewayProps(configProvider.messageGatewaySettings),
        MarketInfoActor.props,
        collaborators => OrderSupervisor.props(
          orderActorProps(collaborators),
          SubmissionSupervisor.props(collaborators.gateway, protocolConstants)
        ),
        bitcoinPeerProps,
        OkPayProcessorActor.props(configProvider.okPaySettings)
      )
      Props(new CoinffeinePeerActor(ownPort, BrokerAddress(brokerHostname, brokerPort), props))
    }

    private def orderActorProps(orderSupervisorCollaborators: OrderSupervisorCollaborators)
                               (order: Order[_ <: FiatCurrency], submissionSupervisor: ActorRef) = {
      import orderSupervisorCollaborators._
      val collaborators = OrderActor.Collaborators(
        wallet, paymentProcessor, submissionSupervisor, gateway, bitcoinPeer)
      OrderActor.props(exchangeActorProps, network, exchangeAmountsCalculator, order, collaborators)
    }
  }
}
