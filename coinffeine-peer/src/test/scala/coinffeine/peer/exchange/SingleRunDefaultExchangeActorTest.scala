package coinffeine.peer.exchange

import scala.util.{Failure, Success}

import coinffeine.model.bitcoin._
import coinffeine.model.exchange._
import coinffeine.peer.bitcoin.wallet.WalletActor
import coinffeine.peer.exchange.DepositWatcher._
import coinffeine.peer.exchange.ExchangeActor._
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor

class SingleRunDefaultExchangeActorTest extends DefaultExchangeActorTest {

  "The exchange actor" should "report an exchange success on the happy path" in new Fixture {
    startActor()
    givenMicropaymentChannelSuccess()
    notifyDepositDestination(CompletedChannel)
    listener.expectMsg(ExchangeSuccess(completedExchange))
    listener.expectTerminated(actor)
  }

  it should "forward progress reports" in new Fixture {
    startActor()
    listener.expectNoMsg()
    val progressUpdate = ExchangeUpdate(runningExchange.completeStep(1))
    micropaymentChannelActor.probe.send(actor, progressUpdate)
    listener.expectMsg(progressUpdate)
    givenMicropaymentChannelCreation()
    micropaymentChannelActor.probe.send(actor, progressUpdate)
    listener.expectMsg(progressUpdate)
    system.stop(actor)
  }

  it should "report a failure if the handshake couldn't even start" in new Fixture {
    givenFailingUserInfoLookup()
    startActor()
    listener.expectMsgType[ExchangeFailure]
    listener.expectTerminated(actor)
  }

  it should "report a failure if the handshake fails" in new Fixture {
    givenFailingHandshake()
    startActor()
    listener.expectMsgType[ExchangeFailure]
    listener.expectTerminated(actor)
  }

  it should "report a failure and ask the broadcaster publish the refund if commitments are invalid" in
    new Fixture {
      givenHandshakeWillSucceedWithInvalidCounterpartCommitment()
      startActor()
      listener.expectNoMsg()
      notifyDepositDestination(DepositRefund)
      inside (listener.expectMsgType[ExchangeFailure].exchange.state.cause) {
        case Exchange.Abortion(Exchange.InvalidCommitments(Both(Failure(_), Success(_)))) =>
      }
      listener.expectTerminated(actor)
    }

  it should "report a failure if the actual exchange fails" in new Fixture {
    startActor()

    val error = new Error("exchange failure")
    givenMicropaymentChannelCreation()
    micropaymentChannelActor.probe.send(actor, MicroPaymentChannelActor.ChannelFailure(1, error))

    notifyDepositDestination(ChannelAtStep(1))

    listener.expectMsgType[ExchangeFailure]
    listener.expectTerminated(actor)
  }

  it should "report a failure if the broadcast failed" in new Fixture {
    givenBroadcasterWillFail()
    startActor()
    givenMicropaymentChannelSuccess()
    listener.expectMsgType[ExchangeFailure].exchange.state.cause shouldBe Exchange.NoBroadcast
    listener.expectTerminated(actor)
  }

  it should "report a failure if the broadcast succeeds with an unexpected transaction" in
    new Fixture {
      val unexpectedTx = ImmutableTransaction {
        val newTx = dummyTx.get
        newTx.setLockTime(40)
        newTx
      }
      givenBroadcasterWillSucceed(unexpectedTx)
      startActor()
      givenMicropaymentChannelSuccess()
      notifyDepositDestination(UnexpectedDestination, unexpectedTx)
      inside(listener.expectMsgType[ExchangeFailure].exchange.state) {
        case Exchange.Failed(Exchange.UnexpectedBroadcast, _, _, Some(`unexpectedTx`)) =>
      }
      listener.expectTerminated(actor)
    }

  it should "report a failure if the broadcast is forcefully finished because it took too long" in
    new Fixture {
      val midWayTx = ImmutableTransaction {
        val newTx = dummyTx.get
        newTx.setLockTime(40)
        newTx
      }
      givenBroadcasterWillPanic(midWayTx)
      startActor()
      givenMicropaymentChannelCreation()
      notifyDepositDestination(DepositRefund, midWayTx)
      val failedState = listener.expectMsgType[ExchangeFailure].exchange.state
      failedState.cause shouldBe Exchange.PanicBlockReached
      failedState.transaction.value shouldBe midWayTx
      listener.expectTerminated(actor)
    }

  it should "unblock funds on termination" in new Fixture {
    startActor()
    system.stop(actor)
    walletActor.expectMsg(WalletActor.UnblockBitcoins(exchangeId))
  }
}
