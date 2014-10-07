package coinffeine.protocol.messages.handshake

import coinffeine.model.bitcoin._
import coinffeine.model.exchange.ExchangeId
import coinffeine.protocol.messages.PublicMessage

case class ExchangeCommitment(
  exchangeId: ExchangeId,
  publicKey: PublicKey,
  commitmentTransaction: ImmutableTransaction
) extends PublicMessage {
  require(!publicKey.canSign, "Just the public key is needed")
}
