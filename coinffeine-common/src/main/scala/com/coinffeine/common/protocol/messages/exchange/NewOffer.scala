package com.coinffeine.common.protocol.messages.exchange

import com.google.bitcoin.core.Transaction

import com.coinffeine.common.protocol.messages.PublicMessage

case class NewOffer(exchangeId: String, offer: Transaction) extends PublicMessage
