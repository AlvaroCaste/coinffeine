package coinffeine.peer.exchange.protocol.impl

import scala.util.Try
import scala.util.control.NonFatal

import coinffeine.model.bitcoin.{ImmutableTransaction, TransactionSignature}
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange.{RunningExchange, Both}
import coinffeine.peer.exchange.protocol.MicroPaymentChannel._
import coinffeine.peer.exchange.protocol._
import coinffeine.peer.exchange.protocol.impl.DefaultMicroPaymentChannel._

private[impl] class DefaultMicroPaymentChannel[C <: FiatCurrency] private (
    override val exchange: RunningExchange[C], override val currentStep: Step)
  extends MicroPaymentChannel[C] {

  def this(exchange: RunningExchange[C]) =
    this(exchange, IntermediateStep(1, exchange.amounts.breakdown))

  private val currentUnsignedTransaction = ImmutableTransaction {
    import exchange.amounts._

    val split = if (currentStep.isFinal) Both(
      buyer = buyerDeposit + bitcoinAmount,
      seller = sellerDeposit - bitcoinAmount
    ) else Both(
      buyer = stepBitcoinAmount * (currentStep.value - 1),
      seller = bitcoinAmount - stepBitcoinAmount * (currentStep.value - 1)
    )

    TransactionProcessor.createUnsignedTransaction(
      inputs = exchange.state.deposits.transactions.toSeq.map(_.get.getOutput(0)),
      outputs = Seq(
        exchange.participants.buyer.bitcoinKey -> split.buyer,
        exchange.participants.seller.bitcoinKey -> split.seller
      ),
      network = exchange.parameters.network
    )
  }

  override def validateCurrentTransactionSignatures(
      herSignatures: Both[TransactionSignature]): Try[Unit] = {
    val tx = currentUnsignedTransaction.get
    val herKey = exchange.state.counterpart.bitcoinKey

    def requireValidSignature(index: Int, signature: TransactionSignature) = {
      require(
        TransactionProcessor.isValidSignature(tx, index, signature, herKey,
          exchange.requiredSignatures.toSeq),
        s"Signature $signature cannot satisfy ${tx.getInput(index)}"
      )
    }

    Try {
      requireValidSignature(BuyerDepositInputIndex, herSignatures.buyer)
      requireValidSignature(SellerDepositInputIndex, herSignatures.seller)
    } recover {
      case NonFatal(cause) => throw InvalidSignaturesException(herSignatures, cause)
    }
  }

  override def signCurrentTransaction = {
    val tx = currentUnsignedTransaction.get
    val signingKey = exchange.state.user.bitcoinKey
    Both(
      buyer = TransactionProcessor.signMultiSignedOutput(
        tx, BuyerDepositInputIndex, signingKey, exchange.requiredSignatures.toSeq),
      seller = TransactionProcessor.signMultiSignedOutput(
        tx, SellerDepositInputIndex, signingKey, exchange.requiredSignatures.toSeq)
    )
  }

  override def nextStep = new DefaultMicroPaymentChannel(exchange, currentStep.next)

  override def closingTransaction(herSignatures: Both[TransactionSignature]) = {
    validateCurrentTransactionSignatures(herSignatures).get
    val tx = currentUnsignedTransaction.get
    val signatures = Seq(signCurrentTransaction, herSignatures)
    val buyerSignatures = signatures.map(_.buyer)
    val sellerSignatures = signatures.map(_.seller)
    TransactionProcessor.setMultipleSignatures(tx, BuyerDepositInputIndex, buyerSignatures: _*)
    TransactionProcessor.setMultipleSignatures(tx, SellerDepositInputIndex, sellerSignatures: _*)
    ImmutableTransaction(tx)
  }
}

private[impl] object DefaultMicroPaymentChannel {
  val BuyerDepositInputIndex = 0
  val SellerDepositInputIndex = 1
}
