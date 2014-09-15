package coinffeine.peer.market.orders.controller

import coinffeine.model.currency.FiatCurrency

/** Entity able to publish an order to a market */
trait OrderPublication[C <: FiatCurrency] {
  def addListener(listener: OrderPublication.Listener): Unit
  def isInMarket: Boolean
  def keepPublishing(): Unit
  def stopPublishing(): Unit
}

object OrderPublication {
  trait Listener {
    def inMarket(): Unit
    def offline(): Unit
  }
}
