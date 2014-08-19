package coinffeine.peer.exchange

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.BitcoinPeerActor._
import coinffeine.peer.bitcoin.BlockchainActor._
import coinffeine.peer.exchange.TransactionBroadcastActor._
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor.{GetLastOffer, LastOffer}
import coinffeine.peer.exchange.util.ConstantValueActor
import coinffeine.peer.exchange.util.ConstantValueActor.SetValue

class TransactionBroadcastActor(constants: ProtocolConstants)
  extends Actor with ActorLogging with Stash {

  override val receive: Receive = {
    case msg: StartBroadcastHandling =>
      msg.bitcoinPeerActor ! RetrieveBlockchainActor
      context.become(waitForBlockchain(msg))
  }

  private def waitForBlockchain(init: StartBroadcastHandling): Receive = {
    case BlockchainActorRef(blockchain: ActorRef) =>
      unstashAll()
      new InitializedBroadcastActor(init, blockchain).start()
    case _ => stash()
  }

  private class InitializedBroadcastActor(init: StartBroadcastHandling, blockchain: ActorRef) {
    import init._

    private var micropaymentChannel: ActorRef = {
      val constantValueActor = context.actorOf(Props[ConstantValueActor])
      constantValueActor ! SetValue(LastOffer(None))
      constantValueActor
    }

    private def autoNotifyBlockchainHeightWith(height: Long, msg: Any): Unit = {
      import context.dispatcher
      implicit val timeout = Timeout(1.day)
      (blockchain ? WatchBlockchainHeight(height))
        .mapTo[BlockchainHeightReached]
        .onSuccess { case _ =>
          self ! msg
        }
    }

    private def setTimePanicFinish(): Unit = {
      val panicBlock = refund.get.getLockTime - constants.refundSafetyBlockCount
      autoNotifyBlockchainHeightWith(panicBlock, FinishExchange)
    }

    private def finishWith(result: Any): Unit = {
      resultListeners.foreach { _ ! result}
      context.stop(self)
    }

    private def broadcastCompleted(txToPublish: ImmutableTransaction): Receive = {
      case msg @ TransactionPublished(`txToPublish`, _) =>
        finishWith(ExchangeFinished(msg))
      case TransactionPublished(_, unexpectedTx) =>
        finishWith(ExchangeFinishFailure(UnexpectedTxBroadcast(unexpectedTx)))
      case TransactionNotPublished(_, err) =>
        finishWith(ExchangeFinishFailure(err))
    }

    private def readyForBroadcast(offer: ImmutableTransaction): Receive = {
      case ReadyForBroadcast =>
        bitcoinPeerActor ! PublishTransaction(offer)
        context.become(broadcastCompleted(offer))
    }

    private val gettingLastOffer: Receive = {
      case LastOffer(offer) =>
        val bestOffer = offer.getOrElse(refund).get
        if (bestOffer.isTimeLocked)
          autoNotifyBlockchainHeightWith(bestOffer.getLockTime, ReadyForBroadcast)
        else
          self ! ReadyForBroadcast
        context.become(readyForBroadcast(ImmutableTransaction(bestOffer)))
    }

    private val handleFinishExchange: Receive = {
      case FinishExchange =>
        micropaymentChannel ! GetLastOffer
        context.become(gettingLastOffer)
    }

    private val setMicropaymentChannel: Receive = {
      case SetMicropaymentActor(microPaymentRef) =>
        micropaymentChannel = microPaymentRef
        context.become(handleFinishExchange)
    }

    def start(): Unit = {
      setTimePanicFinish()
      context.become(handleFinishExchange orElse setMicropaymentChannel)
    }
  }
}

/** This actor is in charge of broadcasting the appropriate transactions for an exchange, whether
  * the exchange ends successfully or not.
  */
object TransactionBroadcastActor {

  def props(constants: ProtocolConstants) = Props(new TransactionBroadcastActor(constants))

  /** A request to the actor to start the necessary broadcast handling. It sets the refund
    * transaction to be used. This transaction will be broadcast as soon as its timelock expires if
    * there are no better alternatives (like broadcasting the successful exchange transaction)
    */
  case class StartBroadcastHandling(
    refund: ImmutableTransaction, bitcoinPeerActor: ActorRef, resultListeners: Set[ActorRef])

  /** Sets the micropayment actor, which will be queried for transactions which are deemed better
    * than the refund transaction.
    */
  case class SetMicropaymentActor(micropaymentRef: ActorRef)

  /** A request for the actor to finish the exchange and broadcast the best possible transaction */
  case object FinishExchange

  /** A message sent to the listeners indicating that the exchange could be finished by broadcasting
    * a transaction. This message can also be sent once the micropayment actor has been set if the
    * exchange has been forcefully closed due to the risk of having the refund exchange be valid.
    */
  case class ExchangeFinished(publishedTransaction: TransactionPublished)

  /** A message sent to the listeners indicating that the broadcast of the best transaction was not
    * performed due to an error.
    */
  case class ExchangeFinishFailure(cause: Throwable)

  case class UnexpectedTxBroadcast(unexpectedTx: ImmutableTransaction) extends RuntimeException(
    "The exchange finished with a successful broadcast, but the transaction that was published was" +
      s"not the one that was being expected: $unexpectedTx")

  private case object ReadyForBroadcast
}
