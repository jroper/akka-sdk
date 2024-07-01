/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package kalix.javasdk.impl.view

import java.util.Optional
import kalix.javasdk.view.UpdateContext
import kalix.javasdk.view.View
import kalix.javasdk.view.View.Effect

abstract class ViewUpdateRouter {
  def _internalHandleUpdate(state: Option[Any], event: Any, context: UpdateContext): Effect[_]
}

abstract class ViewRouter[S, V <: View[S]](protected val view: V) extends ViewUpdateRouter {

  /** INTERNAL API */
  override final def _internalHandleUpdate(state: Option[Any], event: Any, context: UpdateContext): Effect[_] = {
    val stateOrEmpty: S = state match {
      case Some(preExisting) => preExisting.asInstanceOf[S]
      case None              => view.emptyState()
    }
    try {
      view._internalSetUpdateContext(Optional.of(context))
      handleUpdate(context.eventName(), stateOrEmpty, event)
    } catch {
      case missing: UpdateHandlerNotFound =>
        throw new ViewException(
          context.viewId,
          missing.eventName,
          "No update handler found for event [" + missing.eventName + "] on " + view.getClass.toString,
          Option.empty)
    } finally {
      view._internalSetUpdateContext(Optional.empty())
    }
  }

  def handleUpdate(commandName: String, state: S, event: Any): Effect[S]

}

abstract class ViewMultiTableRouter extends ViewUpdateRouter {

  /** INTERNAL API */
  override final def _internalHandleUpdate(state: Option[Any], event: Any, context: UpdateContext): Effect[_] = {
    viewRouter(context.eventName())._internalHandleUpdate(state, event, context)
  }

  def viewRouter(eventName: String): ViewRouter[_, _]

}
