package coinffeine.gui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success}
import scalafx.application.JFXApp.PrimaryStage
import scalafx.application.{JFXApp, Platform}

import org.controlsfx.dialog.Dialogs

import coinffeine.gui.application.main.MainView
import coinffeine.gui.application.operations.OperationsView
import coinffeine.gui.application.{ApplicationProperties, ApplicationScene, NotificationManager}
import coinffeine.gui.control.{BitcoinConnectionStatusWidget, WalletBalanceWidget}
import coinffeine.gui.setup.CredentialsValidator.Result
import coinffeine.gui.setup.{CredentialsValidator, SetupWizard}
import coinffeine.model.bitcoin.IntegrationTestNetworkComponent
import coinffeine.model.currency.Currency.{Bitcoin, Euro}
import coinffeine.peer.api.impl.ProductionCoinffeineApp
import coinffeine.peer.payment.okpay.OkPayCredentials

object Main extends JFXApp
  with ProductionCoinffeineApp.Component with IntegrationTestNetworkComponent {

  JFXApp.AUTO_SHOW = false

  val validator = new CredentialsValidator {
    override def apply(credentials: OkPayCredentials): Future[Result] = Future {
      Thread.sleep(2000)
      if (Random.nextBoolean()) CredentialsValidator.ValidCredentials
      else CredentialsValidator.InvalidCredentials("Random failure")
    }
  }
  val sampleAddress = "124U4qQA7g33C4YDJFpwqXd2XJiA3N6Eb7"
  val setupConfig = new SetupWizard(sampleAddress, validator).run()

  val connectResult = app.network.connect()
  Await.ready(connectResult, Duration.Inf)
  connectResult.value.get match {
    case Success(_) =>
      val properties = new ApplicationProperties(app)
      val notificationManager = new NotificationManager(app)
      stage = new PrimaryStage {
        title = "Coinffeine"
        scene = new ApplicationScene(
          views = Seq(new MainView, new OperationsView(app, properties)),
          toolbarWidgets = Seq(
            new WalletBalanceWidget(Bitcoin, properties.walletBalanceProperty),
            new WalletBalanceWidget(Euro, properties.fiatBalanceProperty)
          ),
          statusBarWidgets = Seq(
            new BitcoinConnectionStatusWidget(properties.bitcoinConnectionStatusProperty)
          )
        )
      }
      stage.show()
    case Failure(err) =>
      Dialogs.create()
        .title("Connection error")
        .message("Could not connect to the Coinffeine network. The application will now exit.")
        .showException(err)
      Platform.exit()
  }


  override def stopApp(): Unit = {
    app.close()
  }
}
