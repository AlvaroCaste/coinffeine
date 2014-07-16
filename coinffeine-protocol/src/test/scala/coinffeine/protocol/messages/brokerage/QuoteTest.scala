package coinffeine.protocol.messages.brokerage

import coinffeine.common.test.UnitTest
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._

class QuoteTest extends UnitTest {

  "A quote" must "print to a readable string" in {
    Quote(10.EUR -> 20.EUR, 15 EUR).toString should
      be ("Quote(spread = (10 EUR, 20 EUR), last = 15 EUR)")
    Quote(Market(Euro), Some(10 EUR) -> None, None).toString should
      be ("Quote(spread = (10 EUR, --), last = --)")
  }
}
