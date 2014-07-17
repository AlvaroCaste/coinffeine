package coinffeine.peer.api

import scala.concurrent.Future

import coinffeine.model.currency.{BitcoinAmount, FiatAmount, FiatCurrency}
import coinffeine.model.exchange.Exchange
import coinffeine.model.market.{Ask, Bid, OrderBookEntry, OrderId}
import coinffeine.model.network.PeerId

/** Represents how the app takes part on the P2P network */
trait CoinffeineNetwork {

  def status: CoinffeineNetwork.Status

  def peerId: PeerId

  /** Start connection with the network.
    *
    * @return The connected status in case of success or ConnectException otherwise
    */
  def connect(): Future[CoinffeineNetwork.Connected.type]

  /** Disconnect from the network.
    *
    * @return A future to be resolved when actually disconnected from the network.
    */
  def disconnect(): Future[CoinffeineNetwork.Disconnected.type]

  def orders: Set[OrderBookEntry[FiatAmount]]
  def exchanges: Set[Exchange[FiatCurrency]]

  /** Submit an order to buy bitcoins.
    *
    * @param btcAmount           Amount to buy
    * @param fiatAmount          Fiat money to use
    * @return                    A new exchange if submitted successfully
    */
  def submitBuyOrder[F <: FiatAmount](btcAmount: BitcoinAmount, fiatAmount: F): OrderBookEntry[F] =
    submitOrder(OrderBookEntry(Bid, btcAmount, fiatAmount))

  /** Submit an order to sell bitcoins.
    *
    * @param btcAmount           Amount to sell
    * @param fiatAmount          Fiat money to use
    * @return                    A new exchange if submitted successfully
    */
  def submitSellOrder[F <: FiatAmount](btcAmount: BitcoinAmount, fiatAmount: F): OrderBookEntry[F] =
    submitOrder(OrderBookEntry(Ask, btcAmount, fiatAmount))

  /** Submit an order. */
  def submitOrder[F <: FiatAmount](order: OrderBookEntry[F]): OrderBookEntry[F]

  def cancelOrder(order: OrderId): Unit
}

object CoinffeineNetwork {

  sealed trait Status
  case object Disconnected extends Status
  case object Connected extends Status
  case object Connecting extends Status

  case class ConnectException(cause: Throwable)
    extends Exception("Cannot connect to the P2P network", cause)
}
