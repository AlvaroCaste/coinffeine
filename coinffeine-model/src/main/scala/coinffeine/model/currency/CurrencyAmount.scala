package coinffeine.model.currency

import scala.math.BigDecimal.RoundingMode
import scala.util.Try

/** An finite amount of currency C.
  *
  * This trait is used to grant polymorphism to currency amounts. You may combine it with a type
  * parameter in any function in order to accept generic currency amounts, as in:
  * {{{
  *   def myFunction[C <: Currency](amount: CurrencyAmount[C]): Unit { ... }
  * }}}
  *
  * @tparam C The type of currency this amount is represented in
  */
case class CurrencyAmount[C <: Currency](value: BigDecimal, currency: C)
  extends PartiallyOrdered[CurrencyAmount[C]] {

  require(currency.isValidAmount(value), s"Invalid amount for $currency: $value")

  def +(other: CurrencyAmount[C]): CurrencyAmount[C] =
    copy(value = value + other.value)
  def -(other: CurrencyAmount[C]): CurrencyAmount[C] =
    copy(value = value - other.value)
  def * (mult: BigDecimal): CurrencyAmount[C] = copy(value = value * mult)
  def / (divisor: BigDecimal): CurrencyAmount[C] = copy(value = value / divisor)
  def /%(divisor: CurrencyAmount[C]): (Int, CurrencyAmount[C]) = {
    val (division, remainder) = value /% divisor.value
    (division.toIntExact, copy(remainder))
  }
  def unary_- : CurrencyAmount[C] = copy(value = -value)

  def min(that: CurrencyAmount[C]): CurrencyAmount[C] =
    if (this.value <= that.value) this else that
  def max(that: CurrencyAmount[C]): CurrencyAmount[C] =
    if (this.value >= that.value) this else that
  def averageWith(that: CurrencyAmount[C]): CurrencyAmount[C] =
    CurrencyAmount.closestAmount((this.value + that.value) / 2, currency)

  val isPositive = value > 0
  val isNegative = value < 0

  /** Convert this amount to an integer number of the minimum indivisible units. This means
    * cents for Euro/Dollar and Satoshis for Bitcoin. */
  def toIndivisibleUnits: BigInt =
    (value / CurrencyAmount.smallestAmount(currency).value).toBigInt()

  override def tryCompareTo[B >: CurrencyAmount[C] <% PartiallyOrdered[B]](that: B): Option[Int] =
    Try {
      val thatAmount = that.asInstanceOf[CurrencyAmount[_ <: FiatCurrency]]
      require(thatAmount.currency == this.currency)
      thatAmount
    }.toOption.map(thatAmount => this.value.compare(thatAmount.value))

  /** Return a canonical version of the amount with scale set to currency precision */
  def canonical: CurrencyAmount[C] =
    if (value.scale == currency.precision) this
    else copy(value = value.setScale(currency.precision, RoundingMode.UNNECESSARY))

  override def toString = "%s %s".format(canonical.value, currency)

  def numeric: Integral[CurrencyAmount[C]] with Ordering[CurrencyAmount[C]] =
    currency.numeric.asInstanceOf[Integral[CurrencyAmount[C]] with Ordering[CurrencyAmount[C]]]
}

object CurrencyAmount {
  def zero[C <: Currency](currency: C): CurrencyAmount[C] = CurrencyAmount(0, currency)

  def smallestAmount[C <: Currency](currency: C) = {
    val smallestUnit = Seq.fill(currency.precision)(10).foldLeft(BigDecimal(1))(_ / _)
    CurrencyAmount(smallestUnit, currency)
  }

  def fromIndivisibleUnits[C <: Currency](units: BigInt, currency: C): CurrencyAmount[C] =
    smallestAmount(currency) * BigDecimal(units)

  def closestAmount[C <: Currency](value: BigDecimal, currency: C): CurrencyAmount[C] =
    CurrencyAmount(value.setScale(currency.precision, RoundingMode.HALF_EVEN), currency)
}
