package coinffeine.gui.application.launcher

import scala.util.Try
import scalafx.application.JFXApp.PrimaryStage

import coinffeine.gui.application.main.CoinffeinePrimaryStage
import coinffeine.model.bitcoin.network.IntegrationTestNetwork
import coinffeine.peer.api.impl.ProductionCoinffeineApp

trait AppLauncher { this: ProductionCoinffeineApp.Component with IntegrationTestNetwork.Component =>

  private val runWizardAction = new RunWizardAction(configProvider, network)
  private val appStartAction = new AppStartAction(app)
  private val checkForUpdatesAction = new CheckForUpdatesAction()

  def launchApp(): Try[PrimaryStage] = {
    for {
      _ <- runWizardAction()
      _ <- appStartAction()
      _ <- checkForUpdatesAction()
    } yield new CoinffeinePrimaryStage(app, configProvider)
  }
}
