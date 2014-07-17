package coinffeine.peer.api.impl

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor.ActorRef
import akka.pattern._

import coinffeine.model.currency.FiatAmount
import coinffeine.model.exchange.AnyExchange
import coinffeine.model.market.{OrderBookEntry, OrderId}
import coinffeine.peer.CoinffeinePeerActor
import coinffeine.peer.CoinffeinePeerActor.{CancelOrder, OpenOrder, RetrieveOpenOrders, RetrievedOpenOrders}
import coinffeine.peer.api.CoinffeineNetwork
import coinffeine.peer.api.CoinffeineNetwork._

private[impl] class DefaultCoinffeineNetwork(override val peer: ActorRef)
  extends CoinffeineNetwork with PeerActorWrapper {

  private var _status: CoinffeineNetwork.Status = Disconnected

  override def status = _status

  /** @inheritdoc
    *
    * With the centralized broker implementation over protobuf RPC, "connecting" consists on opening
    * a port with a duplex RPC server.
    */
  override def connect(): Future[Connected.type] = {
    _status = Connecting
    val bindResult = (peer ? CoinffeinePeerActor.Connect).flatMap {
      case CoinffeinePeerActor.Connected => Future.successful(Connected)
      case CoinffeinePeerActor.ConnectionFailed(cause) => Future.failed(ConnectException(cause))
    }
    bindResult.onComplete {
      case Success(connected) => _status = connected
      case Failure(_) => _status = Disconnected
    }
    bindResult
  }

  override def disconnect(): Future[Disconnected.type] = ???

  override def exchanges: Set[AnyExchange] = Set.empty

  override def orders: Set[OrderBookEntry[FiatAmount]] =
    await((peer ? RetrieveOpenOrders).mapTo[RetrievedOpenOrders]).orders.toSet

  override def submitOrder[F <: FiatAmount](order: OrderBookEntry[F]): OrderBookEntry[F] = {
    peer ! OpenOrder(order)
    order
  }

  override def cancelOrder(order: OrderId): Unit = {
    peer ! CancelOrder(order)
  }
}
