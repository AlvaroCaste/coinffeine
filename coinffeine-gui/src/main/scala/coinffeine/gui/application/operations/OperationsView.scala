package coinffeine.gui.application.operations

import javafx.beans.binding.Bindings
import scalafx.Includes._
import scalafx.event.ActionEvent
import scalafx.geometry.Insets
import scalafx.scene.control._
import scalafx.scene.layout._

import org.controlsfx.dialog.Dialog.Actions
import org.controlsfx.dialog.Dialogs

import coinffeine.gui.application.properties.OrderProperties
import coinffeine.gui.application.{ApplicationProperties, ApplicationView}
import coinffeine.gui.util.ScalafxImplicits._
import coinffeine.model.currency.{Bitcoin, FiatCurrency}
import coinffeine.model.market._
import coinffeine.peer.api.CoinffeineApp

class OperationsView(app: CoinffeineApp, props: ApplicationProperties) extends ApplicationView {

  private val operationsTable = new OperationsTable(props.ordersProperty)

  private val operationSelectionProperty =
    operationsTable.selected.map(Option(_).map(_.getValue)).toReadOnlyProperty

  private val cancellableOperationProperty =
    operationSelectionProperty.delegate.mapToBool(_.exists(_.isCancellable.value)).toReadOnlyProperty

  private val newOrderButton = new Button {
    id = "newOrderBtn"
    text = "New order"
    handleEvent(ActionEvent.ACTION) { () =>
      val form = new OrderSubmissionForm(app)
      form.show(delegate.getScene.getWindow)
    }
  }

  private val cancelButton = new Button {
    id = "cancelOrderBtn"
    text = "Cancel"
    disable <== Bindings.not(cancellableOperationProperty)
    handleEvent(ActionEvent.ACTION) { () =>
      val confirm = Dialogs.create()
        .title("Order cancellation")
        .message("You are about to cancel the selected operation. Are you sure?")
        .actions(Actions.YES, Actions.NO)
        .showConfirm()
      if (confirm == Actions.YES) {
        operationSelectionProperty.value.foreach {
          case order: OrderProperties =>
            app.network.cancelOrder(order.orderIdProperty.value, "Cancelled by the user")
        }
      }
    }
  }

  override def name: String = "Operations"

  private val buttonsPane: Pane = new HBox {
    content = Seq(newOrderButton, cancelButton)
    spacing = 10
  }

  override def centerPane: Pane = new VBox {
    margin = Insets(20)
    spacing = 10
    content = Seq(buttonsPane, jfxNode2sfx(operationsTable))
  }
}
