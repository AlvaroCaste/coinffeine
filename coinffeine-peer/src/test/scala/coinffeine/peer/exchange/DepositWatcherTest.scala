package coinffeine.peer.exchange

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import com.google.bitcoin.core.TransactionOutPoint

import coinffeine.common.akka.test.AkkaSpec
import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.model.bitcoin.{ImmutableTransaction, KeyPair}
import coinffeine.model.currency.BitcoinAmount
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.{Both, SampleExchange}
import coinffeine.peer.bitcoin.SmartWallet
import coinffeine.peer.bitcoin.blockchain.BlockchainActor
import coinffeine.peer.exchange.DepositWatcher._
import coinffeine.peer.exchange.protocol.MockExchangeProtocol
import coinffeine.peer.exchange.protocol.impl.TransactionProcessor

class DepositWatcherTest extends AkkaSpec with BitcoinjTest with SampleExchange {

  "A deposit watcher" should "notify deposit refund" in new Fixture {
    blockchain.expectMsg(BlockchainActor.WatchOutput(output))
    expectNoMsg(100.millis)
    blockchain.reply(BlockchainActor.OutputSpent(output, myRefund))
    expectMsg(DepositWatcher.DepositSpent(myRefund, DepositRefund))
  }

  it should "notify successful channel publication" in new Fixture {
    blockchain.expectMsg(BlockchainActor.WatchOutput(output))
    val happyPathTx = spendDeposit(exchange.amounts.finalStep.depositSplit.seller)
    blockchain.reply(BlockchainActor.OutputSpent(output, happyPathTx))
    expectMsg(DepositWatcher.DepositSpent(happyPathTx, ChannelCompletion))
  }

  it should "notify other uses of the deposit and how much bitcoin was lost" in new Fixture {
    blockchain.expectMsg(BlockchainActor.WatchOutput(output))
    val interruptedChannelTx = spendDeposit(exchange.amounts.finalStep.depositSplit.seller - 1.BTC)
    blockchain.reply(BlockchainActor.OutputSpent(output, interruptedChannelTx))
    expectMsg(DepositWatcher.DepositSpent(interruptedChannelTx, WrongDepositUse(1.BTC)))
  }

  trait Fixture {
    private val wallet = new SmartWallet(createWallet(new KeyPair(), 100.BTC))
    requiredSignatures.foreach(wallet.delegate.addKey)
    val myDeposit = wallet.createMultisignTransaction(
      coinsId = wallet.blockFunds(100.BTC).get,
      amount = 100.BTC,
      fee = 0.BTC,
      signatures = requiredSignatures
    )
    val output = new TransactionOutPoint(network, 0, myDeposit.get.getHash)
    sendToBlockChain(myDeposit.get)
    val exchange = sellerHandshakingExchange.startExchanging(
      Both(buyer = MockExchangeProtocol.DummyDeposit, seller = myDeposit))
    val myRefund = spendDeposit(exchange.amounts.refunds.seller)
    val blockchain = TestProbe()
    val watcher = system.actorOf(Props(
      new DepositWatcher(exchange, myRefund, Collaborators(blockchain.ref, listener = self))))

    def spendDeposit(getBackAmount: BitcoinAmount) = {
      val tx = TransactionProcessor.createUnsignedTransaction(
        inputs = myDeposit.get.getOutputs.asScala,
        outputs = Seq(participants.seller.bitcoinKey -> getBackAmount),
        network = network
      )
      val signatures = Seq(
        TransactionProcessor.signMultiSignedOutput(
          tx, 0, participants.buyer.bitcoinKey, exchange.requiredSignatures.toSeq),
        TransactionProcessor.signMultiSignedOutput(
          tx, 0, participants.seller.bitcoinKey, exchange.requiredSignatures.toSeq)
      )
      TransactionProcessor.setMultipleSignatures(tx, 0, signatures: _*)
      ImmutableTransaction(tx)
    }
  }
}
