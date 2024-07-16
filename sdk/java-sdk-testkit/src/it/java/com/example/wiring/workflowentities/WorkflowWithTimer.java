/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example.wiring.workflowentities;

import akka.Done;
import com.example.wiring.actions.echo.Message;
import akka.platform.javasdk.annotations.ComponentId;
import akka.platform.javasdk.client.ComponentClient;
import akka.platform.javasdk.workflow.Workflow;
import akka.platform.javasdk.workflow.WorkflowContext;

import java.time.Duration;

@ComponentId("workflow-with-timer")
public class WorkflowWithTimer extends Workflow<FailingCounterState> {

  private final String counterStepName = "counter";

  private final WorkflowContext workflowContext;
  private final ComponentClient componentClient;

  public WorkflowWithTimer(WorkflowContext workflowContext, ComponentClient componentClient) {
    this.workflowContext = workflowContext;
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowDef<FailingCounterState> definition() {
    var counterInc =
      step(counterStepName)
        .asyncCall(() -> {
          var pingWorkflow =
            componentClient
              .forWorkflow(workflowContext.workflowId())
              .method(WorkflowWithTimer::pingWorkflow)
              .deferred(new CounterScheduledValue(12));

          return timers().startSingleTimer("ping", Duration.ofSeconds(2), pingWorkflow);
        })
        .andThen(Done.class, __ -> effects().pause())
        .timeout(Duration.ofMillis(50));


    return workflow()
      .addStep(counterInc);
  }

  public Effect<Message> startFailingCounter(String counterId) {
    return effects()
      .updateState(new FailingCounterState(counterId, 0, false))
      .transitionTo(counterStepName)
      .thenReply(new Message("workflow started"));
  }

  public Effect<Message> startFailingCounterWithReqParam(String counterId) {
    return effects()
      .updateState(new FailingCounterState(counterId, 0, false))
      .transitionTo(counterStepName)
      .thenReply(new Message("workflow started"));
  }

  public Effect<String> pingWorkflow(CounterScheduledValue counterScheduledValue) {
    return effects()
      .updateState(currentState().asFinished(counterScheduledValue.value()))
      .end()
      .thenReply("workflow finished");
  }

  public Effect<FailingCounterState> get() {
    return effects().reply(currentState());
  }
}
