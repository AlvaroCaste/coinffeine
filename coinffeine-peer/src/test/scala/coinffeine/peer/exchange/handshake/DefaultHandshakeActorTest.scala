package coinffeine.peer.exchange.handshake

import akka.actor.ActorRef
import akka.testkit.TestProbe

import coinffeine.model.bitcoin._
import coinffeine.model.exchange.Both
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.wallet.WalletActor
import coinffeine.peer.exchange.ExchangeActor.ExchangeUpdate
import coinffeine.peer.exchange.protocol.{MockExchangeProtocol, MockHandshake}
import coinffeine.peer.exchange.test.CoinffeineClientTest
import coinffeine.peer.exchange.test.CoinffeineClientTest.SellerPerspective
import coinffeine.protocol.messages.arbitration.{CommitmentNotification, CommitmentNotificationAck}
import coinffeine.protocol.messages.handshake._

/** Test fixture for testing the handshake actor interaction, one derived class per scenario. */
abstract class DefaultHandshakeActorTest(systemName: String)
  extends CoinffeineClientTest(systemName) with SellerPerspective {

  def protocolConstants: ProtocolConstants

  lazy val handshake = new MockHandshake(handshakingExchange)
  val listener, blockchain, wallet = TestProbe()
  var actor: ActorRef = _
  startActor()

  def startActor(): Unit = {
    actor = system.actorOf(DefaultHandshakeActor.props(
      DefaultHandshakeActor.ExchangeToStart(exchange, user),
      DefaultHandshakeActor.Collaborators(gateway.ref, blockchain.ref, wallet.ref, listener.ref),
      DefaultHandshakeActor.ProtocolDetails(new MockExchangeProtocol, protocolConstants)
    ))
    listener.watch(actor)
  }

  def restartActor(): Unit = {
    system.stop(actor)
    listener.expectTerminated(actor)
    startActor()
  }

  def givenCounterpartPeerHandshake(): Unit = {
    val peerHandshake =
      PeerHandshake(exchange.id, counterpart.bitcoinKey.publicKey, counterpart.paymentProcessorAccount)
    gateway.relayMessage(peerHandshake, counterpartId)
    listener.expectMsgType[ExchangeUpdate]
  }

  def givenValidRefundSignatureResponse() = {
    val validSignature = RefundSignatureResponse(exchange.id, MockExchangeProtocol.RefundSignature)
    gateway.relayMessage(validSignature, counterpartId)
  }

  def givenCommitmentPublicationNotification(): Unit = {
    val notification = CommitmentNotification(
      exchange.id,
      Both(
        handshake.myDeposit.get.getHash,
        handshake.counterpartCommitmentTransaction.getHash
      )
    )
    gateway.relayMessageFromBroker(notification)
  }

  def shouldCreateDeposits(): Unit = {
    val request = wallet.expectMsgType[WalletActor.CreateDeposit]
    val depositAmounts = exchange.amounts.deposits.seller
    request.amount shouldBe depositAmounts.output
    request.transactionFee shouldBe depositAmounts.fee
    wallet.reply(WalletActor.DepositCreated(request, MockExchangeProtocol.DummyDeposit))
  }

  def shouldForwardPeerHandshake(): Unit = {
    val peerHandshake =
      PeerHandshake(exchange.id, user.bitcoinKey.publicKey, user.paymentProcessorAccount)
    gateway.expectForwarding(peerHandshake, counterpartId)
  }

  def shouldForwardRefundSignatureRequest(): Unit = {
    val refundSignatureRequest = RefundSignatureRequest(exchange.id, handshake.myUnsignedRefund)
    gateway.expectForwarding(refundSignatureRequest, counterpartId)
  }

  def shouldSignCounterpartRefund(): Unit = {
    val request = RefundSignatureRequest(exchange.id, ImmutableTransaction(handshake.counterpartRefund))
    gateway.relayMessage(request, counterpartId)
    val refundSignature =
      RefundSignatureResponse(exchange.id, MockExchangeProtocol.CounterpartRefundSignature)
    gateway.expectForwarding(refundSignature, counterpartId)
  }

  def shouldForwardCommitmentToBroker(): Unit = {
    gateway.expectForwardingToBroker(
      ExchangeCommitment(exchange.id, user.bitcoinKey.publicKey, handshake.myDeposit))
  }

  def shouldAckCommitmentNotification(): Unit = {
    gateway.expectForwardingToBroker(CommitmentNotificationAck(exchange.id))
  }
}
