package coinffeine.peer.exchange.handshake

import scala.concurrent.duration._

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.model.exchange.Both
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.blockchain.BlockchainActor._
import coinffeine.peer.exchange.handshake.HandshakeActor.HandshakeSuccess

class PersistentHandshakeActorTest extends DefaultHandshakeActorTest("happy-path") {

  override def protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitHandshakeMessagesTimeout = 1 minute,
    refundSignatureAbortTimeout = 1 minute
  )

  "A handshake" should "persist successful counterpart handshake" in {
    shouldForwardPeerHandshake()
    givenCounterpartPeerHandshake()
    shouldCreateDeposits()
    blockchain.expectMsgType[WatchMultisigKeys]
    shouldForwardRefundSignatureRequest()
  }

  it should "remember the handshake after a restart" in {
    restartActor()
    blockchain.expectMsgType[WatchMultisigKeys]
    shouldForwardPeerHandshake()
    shouldForwardRefundSignatureRequest()
    shouldSignCounterpartRefund()
  }

  it should "persist valid refund signature received" in {
    givenValidRefundSignatureResponse()
    shouldForwardCommitmentToBroker()
  }

  it should "remember the refund signature after a restart" in {
    restartActor()
    blockchain.expectMsgType[WatchMultisigKeys]
    shouldForwardCommitmentToBroker()
  }

  it should "persist commitment notification" in {
    givenCommitmentPublicationNotification()
    shouldAckCommitmentNotification()
    blockchain.expectMsgAllClassOf(
      classOf[WatchTransactionConfirmation],
      classOf[WatchTransactionConfirmation]
    )
  }

  it should "remember commitment notification after a restart" in {
    restartActor()
    shouldAckCommitmentNotification()
    blockchain.expectMsgAllClassOf(
      classOf[WatchMultisigKeys],
      classOf[WatchTransactionConfirmation],
      classOf[WatchTransactionConfirmation]
    )
  }

  it should "persist successful result when transactions are published" in {
    val expectedCommitments = Both(
      buyer = handshake.myDeposit,
      seller = ImmutableTransaction(handshake.counterpartCommitmentTransaction)
    )
    for (tx <- expectedCommitments.toSeq) {
      blockchain.send(actor, TransactionConfirmed(tx.get.getHash, 1))
    }
    expectedCommitments.foreach { tx =>
      blockchain.expectMsg(RetrieveTransaction(tx.get.getHash))
      blockchain.reply(TransactionFound(tx.get.getHash, tx))
    }
    listener.expectMsgType[HandshakeSuccess]
    listener.expectTerminated(actor)
  }

  it should "remember how it ended and resubmit the final notification" in {
    startActor()
    listener.expectMsgType[HandshakeSuccess]
    listener.expectTerminated(actor)
  }
}
