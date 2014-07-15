package com.coinffeine.common.protocol.messages.brokerage

import coinffeine.model.currency.FiatCurrency
import com.coinffeine.common.protocol.messages.PublicMessage

/** Used to ask about the current open orders of bitcoin traded in a given currency */
case class OpenOrdersRequest(market: Market[FiatCurrency]) extends PublicMessage
