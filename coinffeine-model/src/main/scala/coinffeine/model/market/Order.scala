package coinffeine.model.market

import coinffeine.model.currency._
import coinffeine.model.exchange._

/** An order represents a process initiated by a peer to bid (buy) or ask(sell) bitcoins in
  * the Coinffeine market.
  *
  * The peers of the Coinffeine network are there to exchange bitcoins and fiat money in a
  * decentralized way. When one peer wants to perform a buy or sell operation, he emits
  * an order. Objects of this class represent the state of an order.
  *
  * @param orderType  The type of order (bid or ask)
  * @param amount     The gross amount of bitcoins to bid or ask
  * @param price      The price per bitcoin
  * @param notStarted Whether the order has actually started
  * @param inMarket   Presence on the order book
  * @param cancelled  Whether the order was cancelled
  * @param exchanges  The exchanges that have been initiated to complete this order
  */
case class Order[C <: FiatCurrency](
    id: OrderId,
    orderType: OrderType,
    amount: Bitcoin.Amount,
    price: Price[C],
    notStarted: Boolean,
    inMarket: Boolean,
    cancelled: Boolean,
    exchanges: Map[ExchangeId, Exchange[C]]) {

  val role = Role.fromOrderType(orderType)

  def start: Order[C] = {
    require(notStarted, s"$this has already started")
    copy(notStarted = false)
  }
  def cancel: Order[C] = copy(notStarted = false, inMarket = false, cancelled = true)
  def becomeInMarket: Order[C] = copy(notStarted = false, inMarket = true)
  def becomeOffline: Order[C] = copy(notStarted = false, inMarket = false)

  lazy val amounts: Order.Amounts = Order.Amounts.fromExchanges(amount, role, exchanges)
  lazy val status: OrderStatus =
    if (cancelled) CancelledOrder
    else if (amounts.completed) CompletedOrder
    else if (amounts.exchanging.isPositive) InProgressOrder
    else if (inMarket) InMarketOrder
    else if (notStarted && amounts.notStarted) NotStartedOrder
    else OfflineOrder

  /** Create a new copy of this order with the given exchange. */
  def withExchange(exchange: Exchange[C]): Order[C] =
    if (exchanges.get(exchange.id) == Some(exchange)) this
    else {
      val nextExchanges = exchanges + (exchange.id -> exchange)
      val nextAmounts = Order.Amounts.fromExchanges(amount, role, nextExchanges)
      copy(
        notStarted = notStarted && nextAmounts.notStarted,
        inMarket = false,
        exchanges = nextExchanges
      )
    }

  /** Retrieve the total amount of bitcoins that were already transferred.
    *
    * This count comprise those bitcoins belonging to exchanges that have been completed and
    * exchanges that are in course. That doesn't include the deposits.
    *
    * @return The amount of bitcoins that have been transferred
    */
  def bitcoinsTransferred: Bitcoin.Amount =
    totalSum(Bitcoin.Zero)(e => role.select(e.progress.bitcoinsTransferred))

  /** Retrieve the progress of this order.
    *
    * The progress is measured with a double value in range [0.0, 1.0].
    *
    * @return
    */
  def progress: Double = (bitcoinsTransferred.value / amount.value).toDouble

  def pendingOrderBookEntry: OrderBookEntry[C] = OrderBookEntry(id, orderType, amounts.pending, price)

  def shouldBeOnMarket: Boolean =
    !cancelled && amounts.pending.isPositive && amounts.exchanging.isZero

  private def totalSum[A <: Currency](
      zero: CurrencyAmount[A])(f: Exchange[C] => CurrencyAmount[A]): CurrencyAmount[A] =
    exchanges.values.map(f).foldLeft(zero)(_ + _)
}

object Order {
  case class Amounts(exchanged: Bitcoin.Amount, exchanging: Bitcoin.Amount, pending: Bitcoin.Amount) {
    require((exchanged + exchanging + pending).isPositive)
    def completed: Boolean = exchanging.isZero && pending.isZero
    def notStarted: Boolean = exchanging.isZero && exchanged.isZero
  }

  object Amounts {
    def fromExchanges[C <: FiatCurrency](amount: Bitcoin.Amount,
                                         role: Role,
                                         exchanges: Map[ExchangeId, Exchange[C]]): Amounts = {
      def totalSum(exchanges: Iterable[Exchange[C]]): Bitcoin.Amount =
        exchanges.map(ex => role.select(ex.amounts.exchangedBitcoin)).sum

      val exchangeGroups = exchanges.values.groupBy {
        case _: SuccessfulExchange[_] => 'exchanged
        case _: FailedExchange[_] => 'other
        case _ => 'exchanging
      }.mapValues(totalSum).withDefaultValue(Bitcoin.Zero)

      Order.Amounts(
        exchanged = exchangeGroups('exchanged),
        exchanging = exchangeGroups('exchanging),
        pending = amount - exchangeGroups('exchanged) - exchangeGroups('exchanging)
      )
    }
  }

  def apply[C <: FiatCurrency](id: OrderId,
                               orderType: OrderType,
                               amount: Bitcoin.Amount,
                               price: Price[C]): Order[C] =
    Order(id, orderType, amount, price, notStarted = true, inMarket = false, cancelled = false,
      exchanges = Map.empty)

  /** Creates an order with a random identifier. */
  def random[C <: FiatCurrency](orderType: OrderType, amount: Bitcoin.Amount, price: Price[C]) =
    Order(OrderId.random(), orderType, amount, price)
}
