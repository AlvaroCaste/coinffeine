package coinffeine.peer.bitcoin

import akka.actor.Props
import org.scalatest.concurrent.Eventually

import coinffeine.common.test.AkkaSpec
import coinffeine.model.bitcoin.Implicits._
import coinffeine.model.bitcoin.KeyPair
import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.model.currency.Implicits._
import coinffeine.peer.CoinffeinePeerActor.{RetrieveWalletBalance, WalletBalance}

class WalletActorTest extends AkkaSpec("WalletActorTest") with BitcoinjTest with Eventually {

  "The wallet actor" must "block funds in a multisign transaction" in new Fixture {
    val request = WalletActor.BlockFundsInMultisign(Seq(keyPair, otherKeyPair), 1.BTC)
    instance ! request
    expectMsgPF() {
      case WalletActor.FundsBlocked(`request`, tx) => wallet.value(tx) should be (-1.BTC)
    }
  }

  it must "fail to block funds when there is no enough amount" in new Fixture {
    val request = WalletActor.BlockFundsInMultisign(Seq(keyPair, otherKeyPair), 10000.BTC)
    instance ! request
    expectMsgPF() {
      case WalletActor.FundsBlockingError(`request`, _: IllegalArgumentException) =>
    }
  }

  it must "release blocked funds" in new Fixture {
    val request = WalletActor.BlockFundsInMultisign(Seq(keyPair, otherKeyPair), 1.BTC)
    instance ! request
    val reply = expectMsgType[WalletActor.FundsBlocked]
    instance ! WalletActor.ReleaseFunds(reply.tx)
    eventually {
      wallet.balance() should be(initialFunds)
    }
  }

  it must "report wallet balance" in new Fixture {
    instance ! RetrieveWalletBalance
    expectMsg(WalletBalance(10.BTC))
  }

  trait Fixture {
    val keyPair = new KeyPair
    val otherKeyPair = new KeyPair
    val wallet = createWallet(keyPair, 10.BTC)
    val initialFunds = wallet.balance()
    val instance = system.actorOf(Props(new WalletActor(wallet)))
  }
}
