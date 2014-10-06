package coinffeine.peer.exchange.protocol.impl

import coinffeine.model.bitcoin.{ImmutableTransaction, MutableTransaction, TransactionSignature}
import coinffeine.model.currency._
import coinffeine.model.exchange.HandshakingExchange
import coinffeine.peer.exchange.protocol.Handshake.{InvalidRefundSignature, InvalidRefundTransaction}
import coinffeine.peer.exchange.protocol._

private[impl] class DefaultHandshake[C <: FiatCurrency](
   override val exchange: HandshakingExchange[C],
   override val myDeposit: ImmutableTransaction) extends Handshake[C] {

  override val myUnsignedRefund: ImmutableTransaction = UnsignedRefundTransaction(
    deposit = myDeposit,
    outputKey = exchange.state.user.bitcoinKey,
    outputAmount = exchange.role.select(exchange.amounts.refunds),
    lockTime = exchange.parameters.lockTime,
    network = exchange.parameters.network
  )

  @throws[InvalidRefundTransaction]
  override def signHerRefund(herRefund: ImmutableTransaction) = signRefundTransaction(
    tx = herRefund.get,
    expectedAmount = exchange.role.counterpart.select(exchange.amounts.refunds)
  )

  @throws[InvalidRefundSignature]
  override def signMyRefund(herSignature: TransactionSignature) = {
    if (!TransactionProcessor.isValidSignature(
        myUnsignedRefund.get, index = 0, herSignature,
        signerKey = exchange.state.counterpart.bitcoinKey,
        exchange.requiredSignatures.toSeq)) {
      throw InvalidRefundSignature(myUnsignedRefund, herSignature)
    }
    ImmutableTransaction {
      val tx = myUnsignedRefund.get
      val mySignature = signRefundTransaction(
        tx,
        expectedAmount = exchange.role.select(exchange.amounts.refunds))
      val buyerSignature = exchange.role.buyer(mySignature, herSignature)
      val sellerSignature = exchange.role.seller(mySignature, herSignature)
      TransactionProcessor.setMultipleSignatures(tx, 0, buyerSignature, sellerSignature)
      tx
    }
  }

  private def signRefundTransaction(tx: MutableTransaction,
                                    expectedAmount: Bitcoin.Amount): TransactionSignature = {
    ensureValidRefundTransaction(ImmutableTransaction(tx), expectedAmount)
    TransactionProcessor.signMultiSignedOutput(
      multiSignedDeposit = tx,
      index = 0,
      signAs = exchange.state.user.bitcoinKey,
      exchange.requiredSignatures.toSeq
    )
  }

  private def ensureValidRefundTransaction(tx: ImmutableTransaction,
                                           expectedAmount: Bitcoin.Amount) = {
    def requireProperty(cond: MutableTransaction => Boolean, cause: String): Unit = {
      if (!cond(tx.get)) throw new InvalidRefundTransaction(tx, cause)
    }
    def validateAmount(tx: MutableTransaction): Boolean = {
      val amount: Bitcoin.Amount = tx.getOutput(0).getValue
      amount == expectedAmount
    }
    // TODO: Is this enough to ensure we can sign?
    requireProperty(_.isTimeLocked, "lack a time lock")
    requireProperty(_.getLockTime == exchange.parameters.lockTime, "wrong time lock")
    requireProperty(_.getInputs.size == 1, "should have one input")
    requireProperty(validateAmount, "wrong refund amount")
  }
}
