/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example.wiring.workflowentities;

import com.example.wiring.TestkitConfig;
import com.example.wiring.actions.echo.Message;
import kalix.javasdk.client.ComponentClient;
import kalix.spring.KalixConfigurationTest;
import kalix.spring.testkit.AsyncCallsSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Main.class)
@Import({KalixConfigurationTest.class, TestkitConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
public class SpringWorkflowIntegrationTest extends AsyncCallsSupport {

  @Autowired
  private ComponentClient componentClient;

  @Test
  public void shouldNotStartTransferForWithNegativeAmount() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transferUrl = "/transfer/" + transferId;
    var transfer = new Transfer(walletId1, walletId2, -10);

    Message message =
      await(
        componentClient.forWorkflow(transferId)
          .methodRef(TransferWorkflow::startTransfer)
          .invokeAsync(transfer));

    assertThat(message.text()).isEqualTo("Transfer amount should be greater than zero");
  }

  @Test
  public void shouldTransferMoney() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 10);

    Message response = await(componentClient.forWorkflow(transferId)
      .methodRef(TransferWorkflow::startTransfer)
      .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(90);
        assertThat(balance2).isEqualTo(110);
      });
  }


  @Test
  public void shouldTransferMoneyWithoutStepInputs() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 10);

    Message response = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithoutInputs::startTransfer)
        .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(90);
        assertThat(balance2).isEqualTo(110);
      });
  }

  @Test
  public void shouldTransferAsyncMoneyWithoutStepInputs() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 10);

    Message response = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithoutInputs::startTransferAsync)
        .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(90);
        assertThat(balance2).isEqualTo(110);
      });
  }


  @Test
  public void shouldTransferMoneyWithFraudDetection() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 10);

    Message response = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithFraudDetection::startTransfer)
        .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(90);
        assertThat(balance2).isEqualTo(110);
      });
  }

  @Test
  public void shouldTransferMoneyWithFraudDetectionAndManualAcceptance() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100000);
    createWallet(walletId2, 100000);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 1000);

    Message response = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithFraudDetection::startTransfer)
        .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {

        var transferState = await(
          componentClient.forWorkflow(transferId)
            .methodRef(TransferWorkflowWithFraudDetection::getTransferState)
            .invokeAsync());

        assertThat(transferState.finished).isFalse();
        assertThat(transferState.accepted).isFalse();
        assertThat(transferState.lastStep).isEqualTo("fraud-detection");
      });

    Message acceptedResponse = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithFraudDetection::acceptTransfer)
        .invokeAsync());

    assertThat(acceptedResponse.text()).isEqualTo("transfer accepted");


    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(99000);
        assertThat(balance2).isEqualTo(101000);
      });
  }

  @Test
  public void shouldNotTransferMoneyWhenFraudDetectionRejectTransfer() {
    var walletId1 = "1";
    var walletId2 = "2";
    createWallet(walletId1, 100);
    createWallet(walletId2, 100);
    var transferId = randomTransferId();
    var transfer = new Transfer(walletId1, walletId2, 1000000);

    Message response = await(
      componentClient.forWorkflow(transferId)
        .methodRef(TransferWorkflowWithFraudDetection::startTransfer)
        .invokeAsync(transfer));

    assertThat(response.text()).isEqualTo("transfer started");

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var balance1 = getWalletBalance(walletId1);
        var balance2 = getWalletBalance(walletId2);

        assertThat(balance1).isEqualTo(100);
        assertThat(balance2).isEqualTo(100);

        var transferState = await(
          componentClient.forWorkflow(transferId)
            .methodRef(TransferWorkflowWithFraudDetection::getTransferState)
            .invokeAsync());

        assertThat(transferState.finished).isTrue();
        assertThat(transferState.accepted).isFalse();
        assertThat(transferState.lastStep).isEqualTo("fraud-detection");
      });
  }

  @Test
  public void shouldRecoverFailingCounterWorkflowWithDefaultRecoverStrategy() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithDefaultRecoverStrategy::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        Integer counterValue = getFailingCounterValue(counterId);
        assertThat(counterValue).isEqualTo(3);
      });

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithDefaultRecoverStrategy::get)
            .invokeAsync());

        assertThat(state.finished()).isTrue();
      });
  }

  @Test
  public void shouldRecoverFailingCounterWorkflowWithRecoverStrategy() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithRecoverStrategy::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        Integer counterValue = getFailingCounterValue(counterId);
        assertThat(counterValue).isEqualTo(3);
      });

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithRecoverStrategy::get)
            .invokeAsync());

        assertThat(state.finished()).isTrue();
      });
  }

  @Test
  public void shouldRecoverFailingCounterWorkflowWithRecoverStrategyAndAsyncCall() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithRecoverStrategyAndAsyncCall::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .ignoreExceptions()
      .untilAsserted(() -> {
        Integer counterValue = getFailingCounterValue(counterId);
        assertThat(counterValue).isEqualTo(3);
      });

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .ignoreExceptions()
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithRecoverStrategyAndAsyncCall::get)
            .invokeAsync());
        assertThat(state.finished()).isTrue();
      });
  }

  @Test
  public void shouldRecoverWorkflowTimeout() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithTimeout::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(15, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        Integer counterValue = getFailingCounterValue(counterId);
        assertThat(counterValue).isEqualTo(3);
      });

    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithTimeout::get)
            .invokeAsync());
        assertThat(state.finished()).isTrue();
      });
  }

  @Test
  public void shouldRecoverWorkflowStepTimeout() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithStepTimeout::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .ignoreExceptions()
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithStepTimeout::get)
            .invokeAsync());

        assertThat(state.value()).isEqualTo(2);
        assertThat(state.finished()).isTrue();
      });
  }

  @Test
  public void shouldUseTimerInWorkflowDefinition() {
    //given
    var counterId = randomId();
    var workflowId = randomId();

    //when
    Message response = await(
      componentClient.forWorkflow(workflowId)
        .methodRef(WorkflowWithTimer::startFailingCounter)
        .invokeAsync(counterId));

    assertThat(response.text()).isEqualTo("workflow started");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var state = await(
          componentClient.forWorkflow(workflowId)
            .methodRef(WorkflowWithTimer::get)
            .invokeAsync());

        assertThat(state.finished()).isTrue();
        assertThat(state.value()).isEqualTo(12);
      });
  }


  @Test
  public void shouldNotUpdateWorkflowStateAfterEndTransition() {
    //given
    var workflowId = randomId();
    await(
      componentClient.forWorkflow(workflowId)
        .methodRef(DummyWorkflow::startAndFinish)
        .invokeAsync()
    );
    assertThat(await(
      componentClient.forWorkflow(workflowId)
        .methodRef(DummyWorkflow::get).invokeAsync())).isEqualTo(10);

    //when
    try {
      await(
        componentClient.forWorkflow(workflowId)
          .methodRef(DummyWorkflow::update).invokeAsync());
    } catch (RuntimeException exception) {
      // ignore "500 Internal Server Error" exception from the proxy
    }

    //then
    assertThat(await(
      componentClient.forWorkflow(workflowId)
        .methodRef(DummyWorkflow::get).invokeAsync())).isEqualTo(10);
  }

  @Test
  public void shouldRunWorkflowStepWithoutInitialState() {
    //given
    var workflowId = randomId();

    //when
    String response = await(componentClient.forWorkflow(workflowId)
      .methodRef(WorkflowWithoutInitialState::start).invokeAsync());

    assertThat(response).contains("ok");

    //then
    Awaitility.await()
      .atMost(10, TimeUnit.of(SECONDS))
      .untilAsserted(() -> {
        var state = await(componentClient.forWorkflow(workflowId).methodRef(WorkflowWithoutInitialState::get).invokeAsync());
        assertThat(state).contains("success");
      });
  }


  private String randomTransferId() {
    return randomId();
  }

  private static String randomId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private Integer getFailingCounterValue(String counterId) {
    return await(
      componentClient
        .forEventSourcedEntity(counterId)
        .methodRef(FailingCounterEntity::get).invokeAsync(),
      Duration.ofSeconds(20));
  }

  private void createWallet(String walletId, int amount) {
    await(
      componentClient.forValueEntity(walletId)
        .methodRef(WalletEntity::create)
        .invokeAsync(amount));
  }

  private int getWalletBalance(String walletId) {
    return await(
      componentClient.forValueEntity(walletId)
        .methodRef(WalletEntity::get)
        .invokeAsync()
    ).value;
  }
}
