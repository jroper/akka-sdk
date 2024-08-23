/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.platform.javasdk.impl.action

import akka.Done
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import com.google.protobuf
import com.google.protobuf.any.{ Any => ScalaPbAny }
import kalix.javasdk.actionspec.ActionspecApi
import akka.platform.javasdk.action.Action
import akka.platform.javasdk.action.MessageEnvelope
import akka.platform.javasdk.impl.ActionFactory
import akka.platform.javasdk.impl.JsonMessageCodec
import akka.platform.javasdk.impl.ProxyInfoHolder
import akka.platform.javasdk.spi.DeferredRequest
import akka.platform.javasdk.spi.TimerClient
import kalix.protocol.action.ActionCommand
import kalix.protocol.action.ActionResponse
import kalix.protocol.action.Actions
import kalix.protocol.component.Reply
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inside
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ActionHandlerSpec
    extends ScalaTestWithActorTestKit
    with LogCapturing
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with Inside
    with OptionValues {

  private val classicSystem = system.toClassic

  private val serviceDescriptor =
    ActionspecApi.getDescriptor.findServiceByName("ActionSpecService")
  private val serviceName = serviceDescriptor.getFullName
  private val jsonCodec = new JsonMessageCodec()

  def create(handler: ActionRouter[_]): Actions = {
    val actionFactory: ActionFactory = _ => handler
    val service = new ActionService(actionFactory, serviceDescriptor, Array(), jsonCodec, None)

    val services = Map(serviceName -> service)

    //setting tracing as disabled, emulating that is discovered from the proxy.
    ProxyInfoHolder(system).overrideTracingCollectorEndpoint("")

    new ActionsImpl(
      classicSystem,
      services,
      new TimerClient {
        // Not exercised here
        override def startSingleTimer(
            name: String,
            delay: FiniteDuration,
            maxRetries: Int,
            deferredRequest: DeferredRequest): Future[Done] = ???
        override def removeTimer(name: String): Future[Done] = ???
      })
  }

  "The action service" should {
    "invoke unary commands" in {
      val service = create(new AbstractHandler {

        override def handleUnary(commandName: String, message: MessageEnvelope[Any]): Action.Effect =
          createReplyEffect()
      })

      val reply =
        Await.result(service.handleUnary(ActionCommand(serviceName, "Unary", createInPayload("in"))), 10.seconds)

      inside(reply.response) { case ActionResponse.Response.Reply(Reply(payload, _, _)) =>
        isDoneReply(payload) shouldBe true
      }
    }

    "turn thrown unary command handler exceptions into failure responses" in {
      val service = create(new AbstractHandler {

        override def handleUnary(commandName: String, message: MessageEnvelope[Any]): Action.Effect =
          throw new RuntimeException("boom")
      })

      val reply =
        LoggingTestKit
          .error("Failure during handling of command")
          .expect {
            Await.result(service.handleUnary(ActionCommand(serviceName, "Unary", createInPayload("in"))), 10.seconds)
          }

      inside(reply.response) { case ActionResponse.Response.Failure(fail) =>
        fail.description should startWith("Unexpected error")
      }
    }

    "turn async failure into failure response" in {
      val service = create(new AbstractHandler {

        override def handleUnary(commandName: String, message: MessageEnvelope[Any]): Action.Effect =
          createAsyncReplyEffect(Future.failed(new RuntimeException("boom")))
      })

      val reply =
        LoggingTestKit.error("Failure during handling of command").expect {
          Await.result(service.handleUnary(ActionCommand(serviceName, "Unary", createInPayload("in"))), 10.seconds)
        }
      inside(reply.response) { case ActionResponse.Response.Failure(fail) =>
        fail.description should startWith("Unexpected error")
      }
    }

  }

  private def createReplyEffect(): Action.Effect =
    ActionEffectImpl.ReplyEffect(None)

  private def createAsyncReplyEffect(future: Future[Action.Effect]): Action.Effect =
    ActionEffectImpl.AsyncEffect(future)

  private def createInPayload(field: String) =
    Some(ScalaPbAny.fromJavaProto(protobuf.Any.pack(ActionspecApi.In.newBuilder().setField(field).build())))

  private def isDoneReply(payload: Option[ScalaPbAny]): Boolean = {
    ScalaPbAny.toJavaProto(payload.value).getTypeUrl == "json.kalix.io/akka.Done$"
  }

  class TestAction extends Action

  private abstract class AbstractHandler extends ActionRouter[TestAction](new TestAction) {
    override def handleUnary(commandName: String, message: MessageEnvelope[Any]): Action.Effect =
      ???

  }

}
