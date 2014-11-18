package coinffeine.model.bitcoin

import coinffeine.model.bitcoin.test.BitcoinjTest
import coinffeine.model.currency._

class ImmutableTransactionTest extends BitcoinjTest {

  "Immutable transaction" must "produce the same transaction it was created with" in {
    val wallet = createWallet(1.BTC)
    val origTx = wallet.createSend(new KeyPair().toAddress(network), 0.1.BTC)
    val tx = new ImmutableTransaction(origTx)
    tx.get should be (origTx)
  }

  it must "not be affected by changes in the original transaction" in {
    val wallet = createWallet(1.BTC)
    val origTx = wallet.createSend(new KeyPair().toAddress(network), 0.1.BTC)
    val tx = new ImmutableTransaction(origTx)
    origTx.setLockTime(1000)
    tx.get should not be origTx
  }

  it must "produce independent copies" in {
    val wallet = createWallet(1.BTC)
    val origTx = wallet.createSend(new KeyPair().toAddress(network), 0.1.BTC)
    val tx = new ImmutableTransaction(origTx)
    val copy1 = tx.get
    val copy2 = tx.get
    copy1.setLockTime(1000)
    copy2 should be (origTx)
  }

  it must "accept partial transaction" in {
    val origTx = new MutableTransaction(network)
    new ImmutableTransaction(origTx).get should be (origTx)
  }

  it must "support equality" in {
    val wallet = createWallet(1.BTC)
    val origTx = new MutableTransaction(network)
    val immutableTx1 = new ImmutableTransaction(origTx)
    val immutableTx2 = new ImmutableTransaction(origTx)
    val immutableTx3 = new ImmutableTransaction(
      wallet.createSend(new KeyPair().toAddress(network), 0.1.BTC))

    immutableTx1 should equal (immutableTx1)
    immutableTx1 should equal (immutableTx2)
    immutableTx2 should equal (immutableTx1)
    immutableTx1 should not equal immutableTx3

    immutableTx1.hashCode() should equal (immutableTx2.hashCode())
    immutableTx1.hashCode() should not equal immutableTx3.hashCode()
  }
}
