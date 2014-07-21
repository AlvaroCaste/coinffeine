package coinffeine.peer.exchange

import akka.actor.{Props, ActorRef}

import coinffeine.model.bitcoin._
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange.{Exchange, Role}

/** This actor handles all the necessary steps for an exchange to happen */
object ExchangeActor {

  val HandshakeActorName = "handshake"
  val MicroPaymentChannelActorName = "exchange"
  val TransactionBroadcastActorName = "transactionBroadcast"

  /** This is a request for the actor to start the exchange to be replied with the exchange result. */
  case class StartExchange[C <: FiatCurrency](exchange: Exchange[C],
                                              role: Role,
                                              user: Exchange.PeerInfo,
                                              userWallet: Wallet,
                                              paymentProcessor: ActorRef,
                                              messageGateway: ActorRef,
                                              bitcoinPeer: ActorRef)

  /** This is a message sent to the listeners to indicate that an exchange succeeded */
  case object ExchangeSuccess

  /** This is a message sent to the listeners to indicate that an exchange failed */
  case class ExchangeFailure(e: Throwable)

  case class CommitmentTxNotInBlockChain(txId: Hash) extends RuntimeException(
    s"Handshake reported that the commitment transaction with hash $txId was in " +
      s"blockchain but it could not be found")

  case class UnexpectedTxBroadcast(effectiveTx: ImmutableTransaction, expectedTx: ImmutableTransaction)
    extends RuntimeException(
      s"""The transaction broadcast for this exchange is different from the one that was being expected.
            |   Sent transaction: $effectiveTx
            |   Expected: $expectedTx""".stripMargin)

  case class TxBroadcastFailed(cause: Throwable) extends RuntimeException(
    "The final transaction could not be broadcast", cause)

  case class RiskOfValidRefund(broadcastTx: ImmutableTransaction) extends RuntimeException(
    "The exchange was forcefully finished because it was taking too long and there was a chance" +
      "that the refund transaction could have become valid"
  )

  trait Component {
    def exchangeActorProps: Props
  }
}
