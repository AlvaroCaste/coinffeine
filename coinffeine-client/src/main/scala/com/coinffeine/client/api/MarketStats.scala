package com.coinffeine.client.api

import coinffeine.model.order.OrderBookEntry

import scala.concurrent.Future

import coinffeine.model.currency.{CurrencyAmount, FiatCurrency}
import com.coinffeine.common.protocol.messages.brokerage.{Market, Quote}

/** Give access to current and historical prices and other market stats. */
trait MarketStats {

  /** Check current prices for a given market */
  def currentQuote[C <: FiatCurrency](market: Market[C]): Future[Quote[C]]

  /** Current open orders for a given market (anonymized order book) */
  def openOrders[C <: FiatCurrency](market: Market[C]): Future[Set[OrderBookEntry[CurrencyAmount[C]]]]
}
