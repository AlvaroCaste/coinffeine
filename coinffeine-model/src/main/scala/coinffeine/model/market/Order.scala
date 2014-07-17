package coinffeine.model.market

import coinffeine.model.currency.Currency.Bitcoin
import coinffeine.model.currency._
import coinffeine.model.exchange.Exchange
import coinffeine.model.network.PeerId

/** An order represents a process initiated by a peer to bid (buy) or ask(sell) bitcoins in
  * the Coinffeine market.
  *
  * The peers of the Coinffeine network are there to exchange bitcoins and fiat money in a
  * decentralized way. When one peer wants to perform a buy or sell operation, he emits
  * an order. Objects of this class represent the state of an order.
  *
  * @param owner      The peer that owns the order
  * @param orderType  The type of order (bid or ask)
  * @param status     The current status of the order
  * @param amount     The amount of bitcoins to bid or ask
  * @param price      The price per bitcoin
  * @param exchanges  The exchanges that have been initiated to complete this order
  */
case class Order[+C <: FiatCurrency](
    id: OrderId,
    owner: PeerId,
    orderType: OrderType,
    status: OrderStatus,
    amount: BitcoinAmount,
    price: CurrencyAmount[C],
    exchanges: Seq[Exchange[C]]) {

  /** Create a new copy of this order with the given status. */
  def withStatus(newStatus: OrderStatus): Order[C] = copy(status = newStatus)

  /** Add a new exchange to this order. */
  def add[C1 >: C <: FiatCurrency](exchange: Exchange[C1]): Order[C1] = copy(exchanges = exchanges :+ exchange)

  /** Retrieve the total amount of bitcoins that were already transferred.
    *
    * This count comprise those bitcoins belonging to exchanges that have been completed and
    * exchanges that are in course. That doesn't include the deposits.
    *
    * @return The amount of bitcoins that have been transferred
    */
  def bitcoinsTransferred: BitcoinAmount =
    totalSum(Bitcoin.Zero)(e => e.progress.bitcoinsTransferred)

  /** Retrieve the total amount of fiat money transferred.
    *
    * @return The amount of fiat money that has been transferred.
    */
  def fiatTransferred: FiatAmount =
    totalSum(price.currency.Zero)(e => e.progress.fiatTransferred)

  /** Retrieve the progress of this order.
    *
    * The progress is measured with a double value in range [0.0, 1.0].
    *
    * @return
    */
  def progress: Double = (bitcoinsTransferred.value / amount.value).toDouble

  private def totalSum[A <: Currency](
      zero: CurrencyAmount[A])(f: Exchange[C] => CurrencyAmount[A]): CurrencyAmount[A] =
    exchanges.map(f).reduceOption(_ + _).getOrElse(zero)

}

object Order {

  def apply[C <: FiatCurrency](id: OrderId,
                               owner: PeerId,
                               orderType: OrderType,
                               amount: BitcoinAmount,
                               price: CurrencyAmount[C]): Order[C] =
    Order(id, owner, orderType, status = OfflineOrder, amount, price, exchanges = Seq.empty)

  def apply[C <: FiatCurrency](owner: PeerId,
                               orderType: OrderType,
                               amount: BitcoinAmount,
                               price: CurrencyAmount[C]): Order[C] =
    Order(OrderId.random(), owner, orderType, amount, price)
}
