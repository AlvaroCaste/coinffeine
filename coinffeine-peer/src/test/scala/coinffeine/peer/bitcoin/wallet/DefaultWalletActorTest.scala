package coinffeine.peer.bitcoin.wallet

import scala.concurrent.duration._

import org.scalatest.concurrent.Eventually

import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.model.bitcoin.{KeyPair, MutableWalletProperties}
import coinffeine.model.currency._
import coinffeine.model.exchange.{Both, ExchangeId}
import coinffeine.peer.bitcoin.wallet.WalletActor.{SubscribeToWalletChanges, UnsubscribeToWalletChanges, WalletChanged}

class DefaultWalletActorTest extends AkkaSpec("WalletActorTest") with BitcoinjTest with Eventually {

  "The wallet actor" must "update wallet primary address upon start" in new Fixture {
    eventually {
      properties.primaryAddress.get shouldBe Some(wallet.currentReceiveAddress)
    }
  }

  it must "update the balance upon start" in new Fixture {
    eventually {
      properties.balance.get shouldBe Some(initialBalance)
    }
  }

  it must "create a new transaction" in new Fixture {
    val req = WalletActor.CreateTransaction(1.BTC, someAddress)
    instance ! req
    val WalletActor.TransactionCreated(`req`, tx) = expectMsgType[WalletActor.TransactionCreated]
    Bitcoin.fromSatoshi(tx.get.getValue(wallet.delegate).value) shouldBe (-1.BTC)
  }

  it must "fail to create a new transaction given insufficient balance" in new Fixture {
    val req = WalletActor.CreateTransaction(20.BTC, someAddress)
    instance ! req
    val error = expectMsgType[WalletActor.TransactionCreationFailure]
    error.req shouldBe req
    error.failure.getMessage should include ("Not enough funds blocked")
  }

  it must "create a deposit as a multisign transaction" in new Fixture {
    val funds = givenBlockedFunds(1.1.BTC)
    val request = WalletActor.CreateDeposit(funds, requiredSignatures, 1.BTC, 0.1.BTC)
    instance ! request
    expectMsgPF() {
      case WalletActor.DepositCreated(`request`, tx) =>
        wallet.value(tx.get) shouldBe (-1.1.BTC)
        Bitcoin.fromSatoshi(tx.get.getOutput(0).getValue.value) shouldBe 1.BTC
    }
  }

  it must "fail to create a deposit when there is no enough amount" in new Fixture {
    val funds = givenBlockedFunds(1.BTC)
    val request = WalletActor.CreateDeposit(funds, requiredSignatures, 10000.BTC, 0.BTC)
    instance ! request
    val error = expectMsgType[WalletActor.DepositCreationError]
    error.request shouldBe request
    error.error.getMessage should include ("Not enough funds")
  }

  it must "release unpublished deposit funds" in new Fixture {
    val funds = givenBlockedFunds(1.BTC)
    val request = WalletActor.CreateDeposit(funds, requiredSignatures, 1.BTC, 0.BTC)
    instance ! request
    val reply = expectMsgType[WalletActor.DepositCreated]
    instance ! WalletActor.ReleaseDeposit(reply.tx)
    eventually {
      wallet.estimatedBalance shouldBe initialBalance.amount
    }
  }

  it must "create new key pairs" in new Fixture {
    instance ! WalletActor.CreateKeyPair
    expectMsgClass(classOf[WalletActor.KeyPairCreated])
  }

  it must "update balance property when changed" in new Fixture {
    sendMoneyToWallet(wallet.delegate, 1.BTC)
    val expectedBalance = balancePlusOutputAmount(initialBalance, 1.BTC)
    eventually {
      properties.balance.get shouldBe Some(expectedBalance)
    }
  }

  it must "notify on wallet changes until being unsubscribed" in new Fixture {
    instance ! SubscribeToWalletChanges
    wallet.delegate.importKey(new KeyPair)
    receiveWhile(max = 1.second) {
      case WalletChanged =>
    }
    instance ! UnsubscribeToWalletChanges
    wallet.delegate.importKey(new KeyPair)
    expectNoMsg(1.second)
  }

  trait Fixture {
    val keyPair = new KeyPair
    val otherKeyPair = new KeyPair
    val requiredSignatures = Both(keyPair, otherKeyPair)
    val initialBalance = BitcoinBalance.singleOutput(10.BTC)
    val wallet = new SmartWallet(createWallet(keyPair, initialBalance.amount))
    val properties = new MutableWalletProperties
    val instance = system.actorOf(DefaultWalletActor.props(properties, wallet))
    val someAddress = new KeyPair().toAddress(network)

    def givenBlockedFunds(amount: Bitcoin.Amount): ExchangeId = {
      val fundsId = ExchangeId.random()
      instance ! WalletActor.BlockBitcoins(fundsId, amount)
      expectMsg(WalletActor.BlockedBitcoins(fundsId))
      fundsId
    }

    def balancePlusOutputAmount(balance: BitcoinBalance, amount: Bitcoin.Amount) = balance.copy(
      estimated = balance.estimated + amount,
      available = balance.estimated + amount,
      minOutput = Some(balance.minOutput.fold(amount) { _ min amount }))
  }
}
