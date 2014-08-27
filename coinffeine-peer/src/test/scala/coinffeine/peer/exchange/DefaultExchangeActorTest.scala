package coinffeine.peer.exchange

import scala.concurrent.duration._

import akka.actor.{Props, Terminated}
import akka.testkit.TestProbe
import akka.util.Timeout
import org.scalatest.concurrent.Eventually

import coinffeine.common.akka.test.MockSupervisedActor
import coinffeine.model.bitcoin._
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.Both
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.BitcoinPeerActor._
import coinffeine.peer.bitcoin.BlockchainActor._
import coinffeine.peer.exchange.ExchangeActor._
import coinffeine.peer.exchange.TransactionBroadcastActor.{UnexpectedTxBroadcast => _, _}
import coinffeine.peer.exchange.handshake.HandshakeActor.{HandshakeFailure, HandshakeSuccess, StartHandshake}
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor.StartMicroPaymentChannel
import coinffeine.peer.exchange.protocol.MockExchangeProtocol
import coinffeine.peer.exchange.test.CoinffeineClientTest
import coinffeine.peer.exchange.test.CoinffeineClientTest.SellerPerspective
import coinffeine.peer.payment.MockPaymentProcessorFactory

class DefaultExchangeActorTest extends CoinffeineClientTest("buyerExchange")
  with SellerPerspective with Eventually {

  implicit def testTimeout = new Timeout(5 second)
  private val protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitHandshakeMessagesTimeout = 1 second,
    refundSignatureAbortTimeout = 1 minute
  )

  private val deposits = Both(
    buyer = new Hash(List.fill(64)("0").mkString),
    seller = new Hash(List.fill(64)("1").mkString)
  )
  private val dummyTx = ImmutableTransaction(new MutableTransaction(network))
  private val dummyPaymentProcessor = system.actorOf(
    new MockPaymentProcessorFactory(List.empty)
      .newProcessor(fiatAddress = "", initialBalance = Seq.empty)
  )

  trait Fixture {
    val listener, blockchain, peers, walletActor = TestProbe()
    val handshakeActor, micropaymentChannelActor, transactionBroadcastActor = new MockSupervisedActor()
    val actor = system.actorOf(Props(new DefaultExchangeActor(
      (_, _) => handshakeActor.props,
      _ => micropaymentChannelActor.props,
      transactionBroadcastActor.props,
      new MockExchangeProtocol,
      protocolConstants
    )))
    listener.watch(actor)

    def startExchange(): Unit = {
      listener.send(actor, StartExchange(exchange, user, walletActor.ref,
        dummyPaymentProcessor, gateway.ref, peers.ref))
      peers.expectMsg(RetrieveBlockchainActor)
      peers.reply(BlockchainActorRef(blockchain.ref))
      handshakeActor.expectCreation()
      transactionBroadcastActor.expectCreation()
    }

    def givenHandshakeSuccess(): Unit = {
      handshakeActor.expectAskWithReply {
        case _: StartHandshake[_] => HandshakeSuccess(handshakingExchange, Both.fill(dummyTx), dummyTx)
      }
      transactionBroadcastActor.expectMsg(StartBroadcastHandling(dummyTx, peers.ref, Set(actor)))
    }

    def givenTransactionIsCorrectlyBroadcast(): Unit = {
      transactionBroadcastActor.expectAskWithReply {
        case FinishExchange => ExchangeFinished(TransactionPublished(dummyTx, dummyTx))
      }
    }

    def givenMicropaymentChannelCreation(): Unit = {
      micropaymentChannelActor.expectCreation()
    }

    def givenMicropaymentChannelSuccess(): Unit = {
      givenMicropaymentChannelCreation()
      val initMessage = StartMicroPaymentChannel(runningExchange, dummyPaymentProcessor,
        gateway.ref, Set(actor, transactionBroadcastActor.ref))
      micropaymentChannelActor.expectAskWithReply {
        case `initMessage` => MicroPaymentChannelActor.ExchangeSuccess(Some(dummyTx))
      }
    }

    def shouldWatchForTheTransactions(): Unit = {
      blockchain.expectMsgAllOf(
        WatchPublicKey(counterpart.bitcoinKey),
        WatchPublicKey(user.bitcoinKey))
      blockchain.expectMsgAllOf(
        RetrieveTransaction(deposits.buyer),
        RetrieveTransaction(deposits.seller)
      )
    }
  }

  "The exchange actor" should "report an exchange success when handshake, exchange and broadcast work" in
    new Fixture {
      startExchange()
      givenHandshakeSuccess()
      givenMicropaymentChannelSuccess()
      givenTransactionIsCorrectlyBroadcast()
      listener.expectMsg(ExchangeSuccess(completedExchange))
      listener.expectMsgClass(classOf[Terminated])
      system.stop(actor)
    }

  it should "forward progress reports" in new Fixture {
    startExchange()
    val progressUpdate = ExchangeProgress(runningExchange.increaseProgress(1.BTC, 0.EUR))
    givenHandshakeSuccess()
    givenMicropaymentChannelCreation()
    micropaymentChannelActor.expectAskWithReply {
      case _: StartMicroPaymentChannel[_] => progressUpdate
    }
    listener.expectMsg(progressUpdate)
    system.stop(actor)
  }

  it should "report a failure if the handshake fails" in new Fixture {
    startExchange()
    val error = new Error("Handshake error")
    handshakeActor.expectAskWithReply {
      case _: StartHandshake[_] => HandshakeFailure(error)
    }
    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the actual exchange fails" in new Fixture {
    startExchange()
    givenHandshakeSuccess()

    val error = new Error("exchange failure")
    givenMicropaymentChannelCreation()
    micropaymentChannelActor.expectAskWithReply {
      case _: StartMicroPaymentChannel[_] => MicroPaymentChannelActor.ExchangeFailure(error)
    }

    givenTransactionIsCorrectlyBroadcast()

    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the broadcast failed" in new Fixture {
    startExchange()
    givenHandshakeSuccess()
    givenMicropaymentChannelSuccess()
    val broadcastError = new Error("failed to broadcast")
    transactionBroadcastActor.expectAskWithReply {
      case FinishExchange => ExchangeFinishFailure(broadcastError)
    }
    listener.expectMsg(ExchangeFailure(TxBroadcastFailed(broadcastError)))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the broadcast succeeds with an unexpected transaction" in
    new Fixture {
      startExchange()
      givenHandshakeSuccess()
      givenMicropaymentChannelSuccess()
      val unexpectedTx = ImmutableTransaction {
        val newTx = dummyTx.get
        newTx.setLockTime(40)
        newTx
      }
      transactionBroadcastActor.expectAskWithReply {
        case FinishExchange => ExchangeFinished(TransactionPublished(unexpectedTx, unexpectedTx))
      }
      listener.expectMsg(ExchangeFailure(UnexpectedTxBroadcast(unexpectedTx, dummyTx)))
      listener.expectMsgClass(classOf[Terminated])
      system.stop(actor)
    }

  it should "report a failure if the broadcast is forcefully finished because it took too long" in
    new Fixture {
      startExchange()
      givenHandshakeSuccess()
      givenMicropaymentChannelCreation()
      val midWayTx = ImmutableTransaction {
        val newTx = dummyTx.get
        newTx.setLockTime(40)
        newTx
      }
      transactionBroadcastActor.expectNoMsg()
      transactionBroadcastActor.probe
        .send(actor, ExchangeFinished(TransactionPublished(midWayTx, midWayTx)))
      listener.expectMsg(ExchangeFailure(RiskOfValidRefund(midWayTx)))
      listener.expectMsgClass(classOf[Terminated])
      system.stop(actor)
    }
}
