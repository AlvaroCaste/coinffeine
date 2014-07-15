package coinffeine.protocol.messages.handshake

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.model.exchange.Exchange
import coinffeine.protocol.messages.PublicMessage

case class RefundSignatureRequest(exchangeId: Exchange.Id, refundTx: ImmutableTransaction)
  extends PublicMessage
