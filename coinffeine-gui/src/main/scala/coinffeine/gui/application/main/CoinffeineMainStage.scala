package coinffeine.gui.application.main

import coinffeine.gui.application.operations.OperationsView
import coinffeine.gui.application.operations.validation.DefaultOrderValidation
import coinffeine.gui.application.stats.StatsView
import coinffeine.gui.application.wallet.WalletView
import coinffeine.gui.application.{ApplicationProperties, ApplicationScene}
import coinffeine.gui.control.ConnectionStatusWidget
import coinffeine.gui.control.wallet.{BitcoinBalanceWidget, FiatBalanceWidget}
import coinffeine.gui.util.FxExecutor
import coinffeine.peer.api.CoinffeineApp
import coinffeine.peer.config.ConfigProvider

import scalafx.scene.image.Image
import scalafx.stage.{Stage, StageStyle}

class CoinffeineMainStage(app: CoinffeineApp,
                          configProvider: ConfigProvider) extends Stage(StageStyle.DECORATED) {

  private val properties = new ApplicationProperties(app, FxExecutor.asContext)
  private val orderValidator = new DefaultOrderValidation(app)

  title = "Coinffeine"
  scene = new ApplicationScene(
    views = Seq(
      new OperationsView(app, properties, orderValidator),
      new StatsView(app),
      new WalletView(app, properties.wallet)
    ),
    toolbarWidgets = Seq(
      new BitcoinBalanceWidget(properties.wallet.balance),
      new FiatBalanceWidget(properties.fiatBalanceProperty)
    ),
    statusBarWidgets = Seq(
      new ConnectionStatusWidget(properties.connectionStatusProperty)
    ),
    settingsProvider = configProvider
  )
  icons.add(new Image(this.getClass.getResourceAsStream("/graphics/logo-128x128.png")))
}
