package com.coinffeine.common.protocol.messages.brokerage

import coinffeine.model.currency.{BitcoinAmount, FiatAmount}
import coinffeine.model.exchange.Exchange
import coinffeine.model.market.OrderId
import coinffeine.model.network.PeerId
import com.coinffeine.common.protocol.messages.PublicMessage

/** Represents a coincidence of desires of both a buyer and a seller */
case class OrderMatch(
    orderId: OrderId,
    exchangeId: Exchange.Id,
    amount: BitcoinAmount,
    price: FiatAmount,
    counterpart: PeerId
) extends PublicMessage
