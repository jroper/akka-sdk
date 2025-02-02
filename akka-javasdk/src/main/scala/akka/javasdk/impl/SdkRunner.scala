/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.impl

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionStage
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.FutureConverters._
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import akka.Done
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.http.scaladsl.model.headers.RawHeader
import akka.javasdk.BuildInfo
import akka.javasdk.DependencyProvider
import akka.javasdk.Principals
import akka.javasdk.ServiceSetup
import akka.javasdk.annotations.ComponentId
import akka.javasdk.annotations.Setup
import akka.javasdk.annotations.http.HttpEndpoint
import akka.javasdk.client.ComponentClient
import akka.javasdk.consumer.Consumer
import akka.javasdk.eventsourcedentity.EventSourcedEntity
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext
import akka.javasdk.http.HttpClientProvider
import akka.javasdk.http.RequestContext
import akka.javasdk.impl.Sdk.StartupContext
import akka.javasdk.impl.Validations.Invalid
import akka.javasdk.impl.Validations.Valid
import akka.javasdk.impl.Validations.Validation
import akka.javasdk.impl.action.ActionsImpl
import akka.javasdk.impl.client.ComponentClientImpl
import akka.javasdk.impl.consumer.ConsumerService
import akka.javasdk.impl.eventsourcedentity.EventSourcedEntitiesImpl
import akka.javasdk.impl.eventsourcedentity.EventSourcedEntityService
import akka.javasdk.impl.http.HttpClientProviderImpl
import akka.javasdk.impl.keyvalueentity.KeyValueEntitiesImpl
import akka.javasdk.impl.keyvalueentity.KeyValueEntityService
import akka.javasdk.impl.reflection.Reflect
import akka.javasdk.impl.reflection.Reflect.Syntax.AnnotatedElementOps
import akka.javasdk.impl.timedaction.TimedActionService
import akka.javasdk.impl.timer.TimerSchedulerImpl
import akka.javasdk.impl.view.ViewService
import akka.javasdk.impl.view.ViewsImpl
import akka.javasdk.impl.workflow.WorkflowImpl
import akka.javasdk.impl.workflow.WorkflowService
import akka.javasdk.keyvalueentity.KeyValueEntity
import akka.javasdk.keyvalueentity.KeyValueEntityContext
import akka.javasdk.timedaction.TimedAction
import akka.javasdk.timer.TimerScheduler
import akka.javasdk.view.View
import akka.javasdk.workflow.Workflow
import akka.javasdk.workflow.WorkflowContext
import akka.javasdk.JwtClaims
import akka.javasdk.http.AbstractHttpEndpoint
import akka.javasdk.Tracing
import akka.javasdk.impl.http.JwtClaimsImpl
import akka.javasdk.impl.telemetry.SpanTracingImpl
import akka.javasdk.impl.telemetry.TraceInstrumentation
import akka.runtime.sdk.spi.ComponentClients
import akka.runtime.sdk.spi.HttpEndpointConstructionContext
import akka.runtime.sdk.spi.HttpEndpointDescriptor
import akka.runtime.sdk.spi.RemoteIdentification
import akka.runtime.sdk.spi.SpiComponents
import akka.runtime.sdk.spi.SpiDevModeSettings
import akka.runtime.sdk.spi.SpiEventingSupportSettings
import akka.runtime.sdk.spi.SpiMockedEventingSettings
import akka.runtime.sdk.spi.SpiSettings
import akka.runtime.sdk.spi.StartContext
import akka.stream.Materializer
import com.google.protobuf.Descriptors
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.{ Context => OtelContext }
import kalix.protocol.action.Actions
import kalix.protocol.discovery.Discovery
import kalix.protocol.event_sourced_entity.EventSourcedEntities
import kalix.protocol.replicated_entity.ReplicatedEntities
import kalix.protocol.value_entity.ValueEntities
import kalix.protocol.view.Views
import kalix.protocol.workflow_entity.WorkflowEntities
import org.slf4j.LoggerFactory

import scala.jdk.OptionConverters.RichOptional
import scala.jdk.CollectionConverters._

/**
 * INTERNAL API
 */
@InternalApi
class SdkRunner private (dependencyProvider: Option[DependencyProvider]) extends akka.runtime.sdk.spi.Runner {
  private val startedPromise = Promise[StartupContext]()

  // default constructor for runtime creation
  def this() = this(None)

  // constructor for testkit
  def this(dependencyProvider: java.util.Optional[DependencyProvider]) = this(dependencyProvider.toScala)

  def applicationConfig: Config =
    ApplicationConfig.loadApplicationConf

  override def getSettings: SpiSettings = {
    val applicationConf = applicationConfig
    val devModeSettings =
      if (applicationConf.getBoolean("akka.javasdk.dev-mode.enabled"))
        Some(
          new SpiDevModeSettings(
            httpPort = applicationConf.getInt("akka.javasdk.dev-mode.http-port"),
            aclEnabled = applicationConf.getBoolean("akka.javasdk.dev-mode.acl.enabled"),
            persistenceEnabled = applicationConf.getBoolean("akka.javasdk.dev-mode.persistence.enabled"),
            serviceName = applicationConf.getString("akka.javasdk.dev-mode.service-name"),
            eventingSupport = extractBrokerConfig(applicationConf.getConfig("akka.javasdk.dev-mode.eventing")),
            mockedEventing = SpiMockedEventingSettings.empty,
            testMode = false))
      else
        None

    new SpiSettings(devModeSettings)
  }

  private def extractBrokerConfig(eventingConf: Config): SpiEventingSupportSettings = {
    val brokerConfigName = eventingConf.getString("support")
    SpiEventingSupportSettings.fromConfigValue(
      brokerConfigName,
      if (eventingConf.hasPath(brokerConfigName))
        eventingConf.getConfig(brokerConfigName)
      else
        ConfigFactory.empty())
  }

  override def start(startContext: StartContext): Future[SpiComponents] = {
    try {
      ApplicationConfig(startContext.system).overrideConfig(applicationConfig)
      val app = new Sdk(
        startContext.system,
        startContext.sdkDispatcherName,
        startContext.executionContext,
        startContext.materializer,
        startContext.componentClients,
        startContext.remoteIdentification,
        startContext.tracerFactory,
        dependencyProvider,
        startedPromise)
      Future.successful(app.spiEndpoints)
    } catch {
      case NonFatal(ex) =>
        LoggerFactory.getLogger(getClass).error("Unexpected exception while setting up service", ex)
        startedPromise.tryFailure(ex)
        throw ex
    }
  }

  def started: CompletionStage[StartupContext] =
    startedPromise.future.asJava

}

/**
 * INTERNAL API
 */
@InternalApi
private object ComponentLocator {

  // populated by annotation processor
  private val ComponentDescriptorResourcePath = "META-INF/akka-javasdk-components.conf"
  private val DescriptorComponentBasePath = "akka.javasdk.components"
  private val DescriptorServiceSetupEntryPath = "akka.javasdk.service-setup"

  private val logger = LoggerFactory.getLogger(getClass)

  case class LocatedClasses(components: Seq[Class[_]], service: Option[Class[_]])

  def locateUserComponents(system: ActorSystem[_]): LocatedClasses = {
    val kalixComponentTypeAndBaseClasses: Map[String, Class[_]] =
      Map(
        "http-endpoint" -> classOf[AnyRef],
        "timed-action" -> classOf[TimedAction],
        "consumer" -> classOf[Consumer],
        "event-sourced-entity" -> classOf[EventSourcedEntity[_, _]],
        "workflow" -> classOf[Workflow[_]],
        "key-value-entity" -> classOf[KeyValueEntity[_]],
        "view" -> classOf[AnyRef])

    // Alternative to but inspired by the stdlib SPI style of registering in META-INF/services
    // since we don't always have top supertypes and want to inject things into component constructors
    logger.info("Looking for component descriptors in [{}]", ComponentDescriptorResourcePath)

    // Descriptor hocon has one entry per component type with a list of strings containing
    // the concrete component classes for the given project
    val descriptorConfig = ConfigFactory.load(ComponentDescriptorResourcePath)
    if (!descriptorConfig.hasPath(DescriptorComponentBasePath))
      throw new IllegalStateException(
        "It looks like your project needs to be recompiled. Run `mvn clean compile` and try again.")
    val componentConfig = descriptorConfig.getConfig(DescriptorComponentBasePath)

    val components = kalixComponentTypeAndBaseClasses.flatMap { case (componentTypeKey, componentTypeClass) =>
      if (componentConfig.hasPath(componentTypeKey)) {
        componentConfig.getStringList(componentTypeKey).asScala.map { className =>
          try {
            val componentClass = system.dynamicAccess.getClassFor(className)(ClassTag(componentTypeClass)).get
            logger.debug("Found and loaded component class: [{}]", componentClass)
            componentClass
          } catch {
            case ex: ClassNotFoundException =>
              throw new IllegalStateException(
                s"Could not load component class [$className]. The exception might appear after rename or repackaging operation. " +
                "It looks like your project needs to be recompiled. Run `mvn clean compile` and try again.",
                ex)
          }
        }
      } else
        Seq.empty
    }.toSeq

    if (descriptorConfig.hasPath(DescriptorServiceSetupEntryPath)) {
      // central config/lifecycle class
      val serviceSetupClassName = descriptorConfig.getString(DescriptorServiceSetupEntryPath)
      val serviceSetup = system.dynamicAccess.getClassFor[AnyRef](serviceSetupClassName).get
      if (serviceSetup.hasAnnotation[Setup]) {
        logger.debug("Found and loaded service class setup: [{}]", serviceSetup)
      } else {
        logger.warn("Ignoring service class [{}] as it does not have the the @Setup annotation", serviceSetup)
      }
      LocatedClasses(components, Some(serviceSetup))
    } else {
      LocatedClasses(components, None)
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[javasdk] object Sdk {
  final case class StartupContext(
      componentClients: ComponentClients,
      dependencyProvider: Option[DependencyProvider],
      httpClientProvider: HttpClientProvider)
}

/**
 * INTERNAL API
 */
@InternalApi
private final class Sdk(
    system: ActorSystem[_],
    sdkDispatcherName: String,
    sdkExecutionContext: ExecutionContext,
    sdkMaterializer: Materializer,
    runtimeComponentClients: ComponentClients,
    remoteIdentification: Option[RemoteIdentification],
    tracerFactory: String => Tracer,
    dependencyProviderOverride: Option[DependencyProvider],
    startedPromise: Promise[StartupContext]) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val messageCodec = new JsonMessageCodec
  private val ComponentLocator.LocatedClasses(componentClasses, maybeServiceClass) =
    ComponentLocator.locateUserComponents(system)
  @volatile private var dependencyProviderOpt: Option[DependencyProvider] = dependencyProviderOverride

  private val applicationConfig = ApplicationConfig(system).getConfig
  private val sdkSettings = Settings(applicationConfig.getConfig("akka.javasdk"))

  private val sdkTracerFactory = () => tracerFactory(TraceInstrumentation.InstrumentationScopeName)

  private val httpClientProvider = new HttpClientProviderImpl(
    system,
    None,
    remoteIdentification.map(ri => RawHeader(ri.headerName, ri.headerValue)),
    sdkSettings)

  private lazy val userServiceConfig = {
    // hiding these paths from the config provided to user
    val sensitivePaths = List("akka", "kalix.meta", "kalix.proxy", "kalix.runtime", "system")
    val sdkConfig = applicationConfig.getConfig("akka.javasdk")
    sensitivePaths
      .foldLeft(applicationConfig) { (conf, toHide) => conf.withoutPath(toHide) }
      .withFallback(sdkConfig)
  }

  // validate service classes before instantiating
  private val validation = componentClasses.foldLeft(Valid: Validation) { case (validations, cls) =>
    validations ++ Validations.validate(cls)
  }
  validation match { // if any invalid component, log and throw
    case Valid => ()
    case invalid: Invalid =>
      invalid.messages.foreach { msg => logger.error(msg) }
      invalid.throwFailureSummary()
  }

  // register them if all valid, prototobuf
  private val componentFactories: Map[Descriptors.ServiceDescriptor, Service] = componentClasses
    .filter(hasComponentId)
    .foldLeft(Map[Descriptors.ServiceDescriptor, Service]()) { (factories, clz) =>
      val service = if (classOf[TimedAction].isAssignableFrom(clz)) {
        logger.debug(s"Registering TimedAction [${clz.getName}]")
        timedActionService(clz.asInstanceOf[Class[TimedAction]])
      } else if (classOf[Consumer].isAssignableFrom(clz)) {
        logger.debug(s"Registering Consumer [${clz.getName}]")
        consumerService(clz.asInstanceOf[Class[Consumer]])
      } else if (classOf[EventSourcedEntity[_, _]].isAssignableFrom(clz)) {
        logger.debug(s"Registering EventSourcedEntity [${clz.getName}]")
        eventSourcedEntityService(clz.asInstanceOf[Class[EventSourcedEntity[Nothing, Nothing]]])
      } else if (classOf[Workflow[_]].isAssignableFrom(clz)) {
        logger.debug(s"Registering Workflow [${clz.getName}]")
        workflowService(clz.asInstanceOf[Class[Workflow[Nothing]]])
      } else if (classOf[KeyValueEntity[_]].isAssignableFrom(clz)) {
        logger.debug(s"Registering KeyValueEntity [${clz.getName}]")
        keyValueEntityService(clz.asInstanceOf[Class[KeyValueEntity[Nothing]]])
      } else if (Reflect.isView(clz)) {
        logger.debug(s"Registering View [${clz.getName}]")
        viewService(clz.asInstanceOf[Class[View]])
      } else throw new IllegalArgumentException(s"Component class of unknown component type [$clz]")

      factories.updated(service.descriptor, service)
    }

  private def hasComponentId(clz: Class[_]): Boolean = {
    if (clz.hasAnnotation[ComponentId]) {
      true
    } else {
      //additional check to skip logging for endpoints
      if (!clz.hasAnnotation[HttpEndpoint]) {
        //this could happened when we remove the @ComponentId annotation from the class,
        //the file descriptor generated by annotation processor might still have this class entry,
        //for instance when working with IDE and incremental compilation (without clean)
        logger.warn("Ignoring component [{}] as it does not have the @ComponentId annotation", clz.getName)
      }
      false
    }
  }

  // collect all Endpoints and compose them to build a larger router
  private val httpEndpoints = componentClasses
    .filter(Reflect.isRestEndpoint)
    .map { httpEndpointClass =>
      HttpEndpointDescriptorFactory(httpEndpointClass, httpEndpointFactory(httpEndpointClass))
    }

  // these are available for injecting in all kinds of component that are primarily
  // for side effects
  // Note: config is also always available through the combination with user DI way down below
  private def sideEffectingComponentInjects(span: Option[Span]): PartialFunction[Class[_], Any] = {
    // remember to update component type API doc and docs if changing the set of injectables
    case p if p == classOf[ComponentClient]    => componentClient(span)
    case h if h == classOf[HttpClientProvider] => httpClientProvider(span)
    case t if t == classOf[TimerScheduler]     => timerScheduler(span)
    case m if m == classOf[Materializer]       => sdkMaterializer
  }

  // FIXME mixing runtime config with sdk with user project config is tricky
  def spiEndpoints: SpiComponents = {

    var actionsEndpoint: Option[Actions] = None
    var eventSourcedEntitiesEndpoint: Option[EventSourcedEntities] = None
    var valueEntitiesEndpoint: Option[ValueEntities] = None
    var viewsEndpoint: Option[Views] = None
    var workflowEntitiesEndpoint: Option[WorkflowEntities] = None

    val classicSystem = system.classicSystem

    val services = componentFactories.map { case (serviceDescriptor, service) =>
      serviceDescriptor.getFullName -> service
    }

    val actionAndConsumerServices = services.filter { case (_, service) =>
      service.getClass == classOf[TimedActionService[_]] || service.getClass == classOf[ConsumerService[_]]
    }

    if (actionAndConsumerServices.nonEmpty) {
      actionsEndpoint = Some(
        new ActionsImpl(
          classicSystem,
          actionAndConsumerServices,
          runtimeComponentClients.timerClient,
          sdkExecutionContext,
          sdkTracerFactory))
    }

    services.groupBy(_._2.getClass).foreach {

      case (serviceClass, eventSourcedServices: Map[String, EventSourcedEntityService[_, _, _]] @unchecked)
          if serviceClass == classOf[EventSourcedEntityService[_, _, _]] =>
        val eventSourcedImpl =
          new EventSourcedEntitiesImpl(
            classicSystem,
            eventSourcedServices,
            sdkSettings,
            sdkDispatcherName,
            sdkTracerFactory)
        eventSourcedEntitiesEndpoint = Some(eventSourcedImpl)

      case (serviceClass, entityServices: Map[String, KeyValueEntityService[_, _]] @unchecked)
          if serviceClass == classOf[KeyValueEntityService[_, _]] =>
        valueEntitiesEndpoint = Some(
          new KeyValueEntitiesImpl(classicSystem, entityServices, sdkSettings, sdkDispatcherName, sdkTracerFactory))

      case (serviceClass, workflowServices: Map[String, WorkflowService[_, _]] @unchecked)
          if serviceClass == classOf[WorkflowService[_, _]] =>
        workflowEntitiesEndpoint = Some(
          new WorkflowImpl(
            workflowServices,
            runtimeComponentClients.timerClient,
            sdkExecutionContext,
            sdkDispatcherName,
            sdkTracerFactory))

      case (serviceClass, _: Map[String, TimedActionService[_]] @unchecked)
          if serviceClass == classOf[TimedActionService[_]] =>
      //ignore

      case (serviceClass, _: Map[String, ConsumerService[_]] @unchecked)
          if serviceClass == classOf[ConsumerService[_]] =>
      //ignore

      case (serviceClass, viewServices: Map[String, ViewService[_]] @unchecked)
          if serviceClass == classOf[ViewService[_]] =>
        viewsEndpoint = Some(new ViewsImpl(viewServices, sdkDispatcherName))

      case (serviceClass, _) =>
        sys.error(s"Unknown service type: $serviceClass")
    }

    val serviceSetup: Option[ServiceSetup] = maybeServiceClass match {
      case Some(serviceClassClass) if classOf[ServiceSetup].isAssignableFrom(serviceClassClass) =>
        // FIXME: HttpClientProvider will inject but not quite work for cross service calls until we
        //        pass auth headers with the runner startup context from the runtime
        Some(
          wiredInstance[ServiceSetup](serviceClassClass.asInstanceOf[Class[ServiceSetup]])(
            sideEffectingComponentInjects(None)))
      case _ => None
    }

    val devModeServiceName = sdkSettings.devModeSettings.map(_.serviceName)
    val discoveryEndpoint =
      new DiscoveryImpl(
        classicSystem,
        services,
        AclDescriptorFactory.defaultAclFileDescriptor,
        BuildInfo.name,
        devModeServiceName)

    new SpiComponents {
      override def preStart(system: ActorSystem[_]): Future[Done] = {
        serviceSetup match {
          case None =>
            startedPromise.trySuccess(StartupContext(runtimeComponentClients, None, httpClientProvider))
            Future.successful(Done)
          case Some(setup) =>
            if (dependencyProviderOpt.nonEmpty) {
              logger.info("Service configured with TestKit DependencyProvider")
            } else {
              dependencyProviderOpt = Option(setup.createDependencyProvider())
              dependencyProviderOpt.foreach(_ => logger.info("Service configured with DependencyProvider"))
            }
            startedPromise.trySuccess(
              StartupContext(runtimeComponentClients, dependencyProviderOpt, httpClientProvider))
            Future.successful(Done)
        }
      }

      override def onStart(system: ActorSystem[_]): Future[Done] = {

        serviceSetup match {
          case None => Future.successful(Done)
          case Some(setup) =>
            logger.debug("Running onStart lifecycle hook")
            setup.onStartup()
            Future.successful(Done)
        }
      }

      override def discovery: Discovery = discoveryEndpoint
      override def actions: Option[Actions] = actionsEndpoint
      override def eventSourcedEntities: Option[EventSourcedEntities] = eventSourcedEntitiesEndpoint
      override def valueEntities: Option[ValueEntities] = valueEntitiesEndpoint
      override def views: Option[Views] = viewsEndpoint
      override def workflowEntities: Option[WorkflowEntities] = workflowEntitiesEndpoint
      override def replicatedEntities: Option[ReplicatedEntities] = None
      override def httpEndpointDescriptors: Seq[HttpEndpointDescriptor] = httpEndpoints
    }
  }

  private def timedActionService[A <: TimedAction](clz: Class[A]): TimedActionService[A] =
    new TimedActionService[A](clz, messageCodec, () => wiredInstance(clz)(sideEffectingComponentInjects(None)))

  private def consumerService[A <: Consumer](clz: Class[A]): ConsumerService[A] =
    new ConsumerService[A](clz, messageCodec, () => wiredInstance(clz)(sideEffectingComponentInjects(None)))

  private def workflowService[S, W <: Workflow[S]](clz: Class[W]): WorkflowService[S, W] = {
    new WorkflowService[S, W](
      clz,
      messageCodec,
      { context =>

        val workflow = wiredInstance(clz) {
          sideEffectingComponentInjects(None).orElse {
            // remember to update component type API doc and docs if changing the set of injectables
            case p if p == classOf[WorkflowContext] => context
          }
        }

        // FIXME pull this inline setup stuff out of SdkRunner and into some workflow class
        val workflowStateType: Class[S] = Reflect.workflowStateType(workflow)
        messageCodec.registerTypeHints(workflowStateType)

        workflow
          .definition()
          .getSteps
          .asScala
          .flatMap {
            case asyncCallStep: Workflow.AsyncCallStep[_, _, _] =>
              List(asyncCallStep.callInputClass, asyncCallStep.transitionInputClass)
            case callStep: Workflow.CallStep[_, _, _, _] =>
              List(callStep.callInputClass, callStep.transitionInputClass)
          }
          .foreach(messageCodec.registerTypeHints)

        workflow
      })
  }

  private def eventSourcedEntityService[S, E, ES <: EventSourcedEntity[S, E]](
      clz: Class[ES]): EventSourcedEntityService[S, E, ES] =
    EventSourcedEntityService(
      clz,
      messageCodec,
      context =>
        wiredInstance(clz) {
          // remember to update component type API doc and docs if changing the set of injectables
          case p if p == classOf[EventSourcedEntityContext] => context
        })

  private def keyValueEntityService[S, VE <: KeyValueEntity[S]](clz: Class[VE]): KeyValueEntityService[S, VE] =
    new KeyValueEntityService(
      clz,
      messageCodec,
      context =>
        wiredInstance(clz) {
          // remember to update component type API doc and docs if changing the set of injectables
          case p if p == classOf[KeyValueEntityContext] => context
        })

  private def viewService[V <: View](clz: Class[V]): ViewService[V] =
    new ViewService[V](
      clz,
      messageCodec,
      // remember to update component type API doc and docs if changing the set of injectables
      wiredInstance(_)(PartialFunction.empty))

  private def httpEndpointFactory[E](httpEndpointClass: Class[E]): HttpEndpointConstructionContext => E = {
    (context: HttpEndpointConstructionContext) =>
      lazy val requestContext = new RequestContext {
        override def getPrincipals: Principals =
          PrincipalsImpl(context.principal.source, context.principal.service)

        override def getJwtClaims: JwtClaims =
          context.jwt match {
            case Some(jwtClaims) => new JwtClaimsImpl(jwtClaims)
            case None =>
              throw new RuntimeException(
                "There are no JWT claims defined but trying accessing the JWT claims. The class or the method needs to be annotated with @JWT.")
          }

        override def tracing(): Tracing = new SpanTracingImpl(context.openTelemetrySpan, sdkTracerFactory)
      }
      val instance = wiredInstance(httpEndpointClass) {
        sideEffectingComponentInjects(context.openTelemetrySpan).orElse {
          case p if p == classOf[RequestContext] => requestContext
        }
      }
      instance match {
        case withBaseClass: AbstractHttpEndpoint => withBaseClass._internalSetRequestContext(requestContext)
        case _                                   =>
      }
      instance
  }

  private def wiredInstance[T](clz: Class[T])(partial: PartialFunction[Class[_], Any]): T = {
    // only one constructor allowed
    require(clz.getDeclaredConstructors.length == 1, s"Class [${clz.getSimpleName}] must have only one constructor.")
    wiredInstance(clz.getDeclaredConstructors.head.asInstanceOf[Constructor[T]])(partial)
  }

  /**
   * Create an instance using the passed `constructor` and the mappings defined in `partial`.
   *
   * Each component provider should define what are the acceptable dependencies in the partial function.
   *
   * If the partial function doesn't match, it will try to lookup from a user provided DependencyProvider.
   */
  private def wiredInstance[T](constructor: Constructor[T])(partial: PartialFunction[Class[_], Any]): T = {

    // Note that this function is total because it will always return a value (even if null)
    // last case is a catch all that lookups in the applicationContext
    val totalWireFunction: PartialFunction[Class[_], Any] =
      partial.orElse {
        case p if p == classOf[Config] =>
          userServiceConfig

        // block wiring of clients into anything that is not an Action or Workflow
        // NOTE: if they are allowed, 'partial' should already have a matching case for them
        // if partial func doesn't match, try to lookup in the applicationContext
        case anyOther =>
          dependencyProviderOpt match {
            case _ if platformManagedDependency(anyOther) =>
              //if we allow for a given dependency we should cover it in the partial function for the component
              throw new RuntimeException(
                s"[${constructor.getDeclaringClass.getName}] are not allowed to have a dependency on ${anyOther.getName}");
            case Some(dependencyProvider) =>
              dependencyProvider.getDependency(anyOther)
            case None =>
              throw new RuntimeException(
                s"Could not inject dependency [${anyOther.getName}] required by [${constructor.getDeclaringClass.getName}] as no DependencyProvider was configured.");
          }

      }

    // all params must be wired so we use 'map' not 'collect'
    val params = constructor.getParameterTypes.map(totalWireFunction)

    try constructor.newInstance(params: _*)
    catch {
      case exc: InvocationTargetException if exc.getCause != null =>
        throw exc.getCause
    }
  }

  private def platformManagedDependency(anyOther: Class[_]) = {
    anyOther == classOf[ComponentClient] ||
    anyOther == classOf[TimerScheduler] ||
    anyOther == classOf[HttpClientProvider] ||
    anyOther == classOf[Tracer] ||
    anyOther == classOf[Span] ||
    anyOther == classOf[Config] ||
    anyOther == classOf[WorkflowContext] ||
    anyOther == classOf[EventSourcedEntityContext] ||
    anyOther == classOf[KeyValueEntityContext]
  }

  private def componentClient(openTelemetrySpan: Option[Span]): ComponentClient = {
    ComponentClientImpl(runtimeComponentClients, openTelemetrySpan)(sdkExecutionContext)
  }

  private def timerScheduler(openTelemetrySpan: Option[Span]): TimerScheduler = {
    val metadata = openTelemetrySpan match {
      case None       => MetadataImpl.Empty
      case Some(span) => MetadataImpl.Empty.withTracing(span)
    }
    new TimerSchedulerImpl(messageCodec, runtimeComponentClients.timerClient, metadata)
  }

  private def httpClientProvider(openTelemetrySpan: Option[Span]): HttpClientProvider =
    openTelemetrySpan match {
      case None       => httpClientProvider
      case Some(span) => httpClientProvider.withTraceContext(OtelContext.current().`with`(span))
    }

}
