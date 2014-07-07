package com.coinffeine.client.peer

import scala.concurrent.duration._

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern._
import akka.util.Timeout

import com.coinffeine.client.peer.orders.OrdersActor
import com.coinffeine.common.{Order, PeerConnection}
import com.coinffeine.common.config.ConfigComponent
import com.coinffeine.common.protocol.gateway.MessageGateway
import com.coinffeine.common.protocol.gateway.MessageGateway.{Bind, BindingError, BoundTo}
import com.coinffeine.common.protocol.messages.brokerage
import com.coinffeine.common.protocol.messages.brokerage.{OpenOrdersRequest, QuoteRequest}

/** Implementation of the topmost actor on a peer node. It starts all the relevant actors like
  * the peer actor and the message gateway and supervise them.
  */
class CoinffeinePeerActor(ownAddress: PeerConnection,
                          brokerAddress: PeerConnection,
                          gatewayProps: Props,
                          marketInfoProps: Props,
                          ordersActorProps: Props) extends Actor with ActorLogging {
  import CoinffeinePeerActor._
  import context.dispatcher

  val gatewayRef = context.actorOf(gatewayProps, "gateway")
  val ordersActorRef = {
    val ref = context.actorOf(ordersActorProps, "orders")
    ref ! OrdersActor.Initialize(ownAddress, brokerAddress, gatewayRef)
    ref
  }
  val marketInfoRef = {
    val ref = context.actorOf(marketInfoProps)
    ref ! MarketInfoActor.Start(brokerAddress, gatewayRef)
    ref
  }

  override def receive: Receive = {

    case CoinffeinePeerActor.Connect =>
      implicit val timeout = CoinffeinePeerActor.ConnectionTimeout
      (gatewayRef ? Bind(ownAddress)).map {
        case BoundTo(_) => CoinffeinePeerActor.Connected
        case BindingError(cause) => CoinffeinePeerActor.ConnectionFailed(cause)
      }.pipeTo(sender())

    case BindingError(cause) =>
      log.error(cause, "Cannot start peer")
      context.stop(self)

    case QuoteRequest(market) =>
      marketInfoRef.tell(MarketInfoActor.RequestQuote(market), sender())
    case OpenOrdersRequest(market) =>
      marketInfoRef.tell(MarketInfoActor.RequestOpenOrders(market), sender())

    case openOrder: OpenOrder => ordersActorRef forward openOrder
    case cancelOrder: CancelOrder => ordersActorRef forward cancelOrder
    case message @ RetrieveOpenOrders => ordersActorRef forward message
  }
}

/** Topmost actor on a peer node. */
object CoinffeinePeerActor {

  /** Start peer connection to the network. The sender of this message will receive either
    * a [[Connected]] or [[ConnectionFailed]] message in response. */
  case object Connect
  case object Connected
  case class ConnectionFailed(cause: Throwable)

  /** Open a new order.
    *
    * Note that, in case of having a previous order at the same price, this means an increment
    * of its amount.
    *
    * @param order Order to open
    */
  case class OpenOrder(order: Order)

  /** Cancel an order
    *
    * Note that this can cancel partially an existing order for a greater amount of bitcoin.
    *
    * @param order  Order to cancel
    */
  case class CancelOrder(order: Order)

  /** Ask for own orders opened in any market. */
  case object RetrieveOpenOrders

  /** Reply to [[RetrieveOpenOrders]] message. */
  case class RetrievedOpenOrders(orders: Set[Order])

  /** Ask for the currently open orders. To be replied with an [[brokerage.OpenOrders]]. */
  type RetrieveMarketOrders = brokerage.OpenOrdersRequest

  private val HostSetting = "coinffeine.peer.host"
  private val PortSetting = "coinffeine.peer.port"
  private val BrokerAddressSetting = "coinffeine.broker.address"

  private val ConnectionTimeout = Timeout(10.seconds)

  trait Component { this: OrdersActor.Component with MarketInfoActor.Component
    with MessageGateway.Component with ConfigComponent =>

    lazy val peerProps: Props = {
      val ownAddress = PeerConnection(config.getString(HostSetting), config.getInt(PortSetting))
      val brokerAddress = PeerConnection.parse(config.getString(BrokerAddressSetting))
      Props(new CoinffeinePeerActor(
        ownAddress,
        brokerAddress,
        gatewayProps = messageGatewayProps,
        marketInfoProps,
        ordersActorProps = ordersActorProps
      ))
    }
  }
}
