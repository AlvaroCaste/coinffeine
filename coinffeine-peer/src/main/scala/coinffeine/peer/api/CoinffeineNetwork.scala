package coinffeine.peer.api

import coinffeine.model.currency.{BitcoinAmount, CurrencyAmount, FiatCurrency}
import coinffeine.model.exchange.AnyExchange
import coinffeine.model.market._

/** Represents how the app takes part on the P2P network */
trait CoinffeineNetwork {

  def status: CoinffeineNetwork.Status

  def orders: Set[Order[c] forSome { type c <: FiatCurrency }]

  def exchanges: Set[AnyExchange] = orders.flatMap[AnyExchange, Set[AnyExchange]](_.exchanges.values)

  /** Submit an order to buy bitcoins.
    *
    * @param btcAmount           Amount to buy
    * @param fiatAmount          Fiat money to use
    * @return                    A new exchange if submitted successfully
    */
  def submitBuyOrder[C <: FiatCurrency](btcAmount: BitcoinAmount,
                                        fiatAmount: CurrencyAmount[C]): Order[C] =
    submitOrder(Order(Bid, btcAmount, fiatAmount))

  /** Submit an order to sell bitcoins.
    *
    * @param btcAmount           Amount to sell
    * @param fiatAmount          Fiat money to use
    * @return                    A new exchange if submitted successfully
    */
  def submitSellOrder[C <: FiatCurrency](btcAmount: BitcoinAmount,
                                         fiatAmount: CurrencyAmount[C]): Order[C] =
    submitOrder(Order(Ask, btcAmount, fiatAmount))

  /** Submit an order. */
  def submitOrder[C <: FiatCurrency](order: Order[C]): Order[C]

  /** Cancel an order
    *
    * @param order The order id to be cancelled
    * @param reason A user friendly message that explains why the order is being cancelled
    */
  def cancelOrder(order: OrderId, reason: String): Unit
}

object CoinffeineNetwork {

  sealed trait Status
  case object Disconnected extends Status
  case object Connected extends Status

  case class ConnectException(cause: Throwable)
    extends Exception("Cannot connect to the P2P network", cause)
}
