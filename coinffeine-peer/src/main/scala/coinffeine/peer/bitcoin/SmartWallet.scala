package coinffeine.peer.bitcoin

import java.io.{FileInputStream, File}
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext

import com.google.bitcoin.core.Transaction.SigHash
import com.google.bitcoin.core.{NetworkParameters, TransactionConfidence, Transaction, AbstractWalletEventListener}
import com.google.bitcoin.store.WalletProtobufSerializer
import com.google.bitcoin.wallet.WalletTransaction
import org.joda.time.DateTime

import coinffeine.model.bitcoin._
import coinffeine.model.bitcoin.Implicits._
import coinffeine.model.currency.Currency.Bitcoin
import coinffeine.model.currency._
import coinffeine.model.currency.Implicits._

class SmartWallet(val delegate: Wallet) {

  import SmartWallet._

  def this(network: NetworkParameters) = this(new Wallet(network))

  private val blockedOutputs = {
    val outputs = new BlockedOutputs
    outputs.setSpendCandidates(delegate.calculateAllSpendCandidates(true).toSet)
    outputs
  }

  private var listeners: Set[(Listener, ExecutionContext)] = Set.empty

  def addListener(listener: Listener)(implicit executor: ExecutionContext) = synchronized {
    listeners += listener -> executor
  }

  def loadFromFile(file: File): Unit = {
    val stream = new FileInputStream(file)
    try {
      new WalletProtobufSerializer()
        .readWallet(WalletProtobufSerializer.parseToProto(stream), delegate)
    } finally {
      stream.close()
    }
  }

  def addresses: Seq[Address] = synchronized {
    val network = delegate.getNetworkParameters
    delegate.getKeys.map(_.toAddress(network))
  }

  def balance: BitcoinAmount = synchronized { Currency.Bitcoin.fromSatoshi(delegate.getBalance) }

  def value(tx: MutableTransaction): BitcoinAmount =
    Currency.Bitcoin.fromSatoshi(tx.getValue(delegate))

  def valueSentFromMe(tx: MutableTransaction): BitcoinAmount =
    Currency.Bitcoin.fromSatoshi(tx.getValueSentFromMe(delegate))

  def valueSentToMe(tx: MutableTransaction): BitcoinAmount =
    Currency.Bitcoin.fromSatoshi(tx.getValueSentToMe(delegate))

  def createKeyPair(): KeyPair = synchronized {
    val keyPair = new KeyPair()
    delegate.addKey(keyPair)
    keyPair
  }

  def minOutput: Option[BitcoinAmount] = synchronized { blockedOutputs.minOutput }

  def blockFunds(amount: BitcoinAmount): Option[BlockedCoinsId] = synchronized {
    try {
      blockedOutputs.block(amount)
    } catch {
      case e: BlockedOutputs.NotEnoughFunds => throw new NotEnoughFunds(
        "cannot create multisign transaction: not enough funds", e)
    }
  }

  def unblockFunds(coinsId: BlockedCoinsId): Unit = synchronized {
    blockedOutputs.unblock(coinsId)
  }

  def blockedFunds: BitcoinAmount = synchronized {
    blockedOutputs.blocked
  }

  def createTransaction(amount: BitcoinAmount, to: Address): ImmutableTransaction = synchronized {
    if (amount < blockedOutputs.spendable) ImmutableTransaction(blockFunds(to, amount))
    else throw new NotEnoughFunds(
      s"cannot create a transaction of $amount: not enough funds in wallet")
  }

  def createMultisignTransaction(coinsId: BlockedCoinsId,
                                 amount: BitcoinAmount,
                                 fee: BitcoinAmount,
                                 signatures: Seq[KeyPair]): ImmutableTransaction = synchronized {
    try {
      val inputs = blockedOutputs.use(coinsId, amount + fee)
      ImmutableTransaction(blockMultisignFunds(inputs, signatures, amount, fee))
    } catch {
      case e: BlockedOutputs.NotEnoughFunds => throw new NotEnoughFunds(
        "cannot create multisign transaction: not enough funds", e)
    }
  }

  def releaseTransaction(tx: ImmutableTransaction): Unit = synchronized {
    releaseFunds(tx.get)
    val releasedOutputs = for {
      input <- tx.get.getInputs.toList
      parentTx <- Option(delegate.getTransaction(input.getOutpoint.getHash))
      output <- Option(parentTx.getOutput(input.getOutpoint.getIndex.toInt))
    } yield output
    blockedOutputs.cancelUsage(releasedOutputs.toSet)
  }

  def blockMultisignFunds(requiredSignatures: Seq[PublicKey],
                          amount: BitcoinAmount,
                          fee: BitcoinAmount = Bitcoin.Zero): MutableTransaction = synchronized {
    try {
      blockMultisignFunds(collectFunds(amount), requiredSignatures, amount, fee)
    } catch {
      case e: BlockedOutputs.NotEnoughFunds => throw new NotEnoughFunds(
        "cannot block multisign funds: not enough funds", e)
    }
  }

  private def update(): Unit = synchronized {
    blockedOutputs.setSpendCandidates(delegate.calculateAllSpendCandidates(true).toSet)
    listeners.foreach { case (l, e) => e.execute(new Runnable {
      override def run() = l.onChange()
    })}
  }

  private def blockFunds(tx: MutableTransaction): Unit = {
    delegate.commitTx(tx)
  }

  private def blockFunds(to: Address, amount: BitcoinAmount): MutableTransaction = {
    val tx = delegate.createSend(to, amount.asSatoshi)
    blockFunds(tx)
    tx
  }

  private def blockMultisignFunds(
      inputs: Traversable[MutableTransactionOutput],
      requiredSignatures: Seq[PublicKey],
      amount: BitcoinAmount,
      fee: BitcoinAmount): MutableTransaction = {
    require(amount.isPositive, s"Amount to block must be greater than zero ($amount given)")
    require(!fee.isNegative, s"Fee should be non-negative ($fee given)")
    val totalInputFunds = valueOf(inputs)
    require(totalInputFunds >= amount + fee,
      "Input funds must cover the amount of funds to commit and the TX fee")

    val tx = new MutableTransaction(delegate.getNetworkParameters)
    inputs.foreach(tx.addInput)
    tx.addMultisignOutput(amount, requiredSignatures)
    tx.addChangeOutput(totalInputFunds, amount + fee, delegate.getChangeAddress)
    tx.signInputs(SigHash.ALL, delegate)
    blockFunds(tx)
    tx
  }

  private def releaseFunds(tx: MutableTransaction): Unit = {
    val walletTx = getTransaction(tx.getHash).getOrElse(
      throw new IllegalArgumentException(s"${tx.getHashAsString} is not part of this wallet"))
    walletTx.getInputs.foreach { input =>
      val parentTx = input.getOutpoint.getConnectedOutput.getParentTransaction
      if (contains(parentTx)) {
        if (!input.disconnect()) {
          throw new IllegalStateException(s"cannot disconnect outputs from $input in $walletTx")
        }
        moveToPool(parentTx, WalletTransaction.Pool.UNSPENT)
      }
    }
    moveToPool(walletTx, WalletTransaction.Pool.DEAD)
  }

  private def collectFunds(amount: BitcoinAmount): Set[MutableTransactionOutput] = {
    val inputFundCandidates = delegate.calculateAllSpendCandidates(true)
    val necessaryInputCount =
      inputFundCandidates.view.scanLeft(Currency.Bitcoin.Zero)((accum, output) =>
        accum + Currency.Bitcoin.fromSatoshi(output.getValue))
        .takeWhile(_ < amount)
        .length
    inputFundCandidates.take(necessaryInputCount).toSet
  }

  private def contains(tx: MutableTransaction): Boolean = getTransaction(tx.getHash).isDefined

  private def getTransaction(txHash: Hash) = Option(delegate.getTransaction(txHash))

  private def valueOf(outputs: Traversable[MutableTransactionOutput]): BitcoinAmount =
    outputs.map(funds => Currency.Bitcoin.fromSatoshi(funds.getValue))
      .foldLeft(Bitcoin.Zero)(_ + _)

  private def moveToPool(tx: MutableTransaction, pool: WalletTransaction.Pool): Unit = {
    val wtxs = delegate.getWalletTransactions
    delegate.clearTransactions(0)
    delegate.addWalletTransaction(new WalletTransaction(pool, tx))
    wtxs.foreach { wtx =>
      if (tx.getHash != wtx.getTransaction.getHash) {
        delegate.addWalletTransaction(wtx)
      }
    }
  }

  delegate.addEventListener(new AbstractWalletEventListener {

    override def onTransactionConfidenceChanged(wallet: Wallet, tx: Transaction): Unit = {
      // Don't notify confidence changes for already confirmed transactions to reduce load
      if (tx.getConfidence.getConfidenceType != TransactionConfidence.ConfidenceType.BUILDING ||
        tx.getConfidence.getDepthInBlocks == 1) {
        onChange()
      }
    }

    override def onChange(): Unit = {
      update()
    }
  })
}

object SmartWallet {

  trait Listener {
    def onChange(): Unit
  }

  case class NotEnoughFunds(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
}
