package coinffeine.peer.exchange.protocol.impl

import scala.collection.JavaConversions._
import scala.util.Success

import com.google.bitcoin.core.Transaction.SigHash

import coinffeine.model.bitcoin.{ImmutableTransaction, KeyPair, MutableTransaction, PublicKey}
import coinffeine.model.currency.BitcoinAmount
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.Both

class DepositValidatorTest extends ExchangeTest {

  "Valid deposits" should "not be built from an invalid buyer commitment transaction" in
    new Fixture {
      validator.validate(Both(
        buyer = ImmutableTransaction(invalidFundsCommitment),
        seller = sellerHandshake.myDeposit
      )) should be ('failure)
  }

  it should "not be built from an invalid seller commitment transaction" in new Fixture {
    validator.validate(Both(
      buyer = buyerHandshake.myDeposit,
      seller = ImmutableTransaction(invalidFundsCommitment)
    )) should be ('failure)
  }

  it should "have the same signatures" in new Fixture {
    val signingKey = new KeyPair()
    val wallet = createWallet(signingKey)

    def multisigTx(amount: BitcoinAmount, signatures: Both[PublicKey]) = ImmutableTransaction {
      sendMoneyToWallet(wallet, amount)
      val funds = TransactionProcessor.collectFunds(wallet, amount)
      val tx = TransactionProcessor.createMultiSignedDeposit(funds.toSeq.map(_ -> signingKey), amount,
        wallet.getChangeAddress, signatures.toSeq, network)
      wallet.commitTx(tx)
      tx
    }

    val signatures = Both.fill(new PublicKey())
    val deposits = Both(
      buyer = multisigTx(2.BTC, signatures),
      seller = multisigTx(11.BTC, signatures)
    )
    DepositValidator.validateRequiredSignatures(deposits) should be (Success(signatures))

    val incompatibleDeposits = Both(
      buyer = multisigTx(2.BTC, signatures),
      seller = multisigTx(11.BTC, signatures.copy(seller = new PublicKey))
    )
    DepositValidator.validateRequiredSignatures(incompatibleDeposits) should be ('failure)
  }

  trait Fixture extends BuyerHandshake with SellerHandshake {
    sendMoneyToWallet(sellerWallet, 10.BTC)
    val invalidFundsCommitment = new MutableTransaction(parameters.network)
    invalidFundsCommitment.addInput(sellerWallet.calculateAllSpendCandidates(true).head)
    invalidFundsCommitment.addOutput(5.BTC.asSatoshi, sellerWallet.getKeys.head)
    invalidFundsCommitment.signInputs(SigHash.ALL, sellerWallet)
    val validator = new DepositValidator(amounts, buyerHandshakingExchange.requiredSignatures)
  }
}
