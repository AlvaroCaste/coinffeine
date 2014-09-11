package coinffeine.model.event

import coinffeine.model.currency.Balance
import coinffeine.model.currency.Currency.Bitcoin

/** An event triggered when wallet balance changes. */
case class WalletBalanceChangeEvent(balance: Balance[Bitcoin.type]) extends CoinffeineAppEvent
