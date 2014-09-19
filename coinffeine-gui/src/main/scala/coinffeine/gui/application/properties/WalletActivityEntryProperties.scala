package coinffeine.gui.application.properties

import scalafx.beans.property.{ObjectProperty, ReadOnlyObjectProperty}

import com.google.bitcoin.core.Sha256Hash
import org.joda.time.DateTime

import coinffeine.model.bitcoin.WalletActivity
import coinffeine.model.currency.BitcoinAmount

class WalletActivityEntryProperties(entry: WalletActivity.Entry) {

  private val _time = new ObjectProperty(this, "time", entry.time)
  private val _hash = new ObjectProperty(this, "hash", entry.tx.get.getHash)
  private val _amount = new ObjectProperty(this, "hash", entry.amount)

  val time: ReadOnlyObjectProperty[DateTime] = _time
  val hash: ReadOnlyObjectProperty[Sha256Hash] = _hash
  val amount: ReadOnlyObjectProperty[BitcoinAmount] = _amount

  def update(entry: WalletActivity.Entry): Unit = {
    _time.value = entry.time
    _hash.value = entry.tx.get.getHash
    _amount.value = entry.amount
  }

  def hasHash(h: Sha256Hash): Boolean = hash.value == h
}
