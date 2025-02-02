/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.impl

import java.lang
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import scala.jdk.CollectionConverters._

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.google.protobuf.ByteString
import com.google.protobuf.BytesValue
import com.google.protobuf.any.{ Any => ScalaPbAny }
import com.google.protobuf.{ Any => JavaPbAny }
import AnySupport.BytesPrimitive
import akka.annotation.InternalApi
import akka.javasdk.JsonSupport
import akka.javasdk.annotations.Migration
import akka.javasdk.annotations.TypeName

/**
 * INTERNAL API
 */
@InternalApi
private[javasdk] class JsonMessageCodec extends MessageCodec {

  case class TypeHint(currenTypeHintWithVersion: String, allTypeHints: List[String])

  private val typeHints: ConcurrentMap[Class[_], TypeHint] = new ConcurrentHashMap()
  val reversedTypeHints: ConcurrentMap[String, Class[_]] = new ConcurrentHashMap()

  override def toString: String = s"JsonMessageCodec: ${typeHints.keySet().size()} registered types"

  /**
   * In the Java SDK, output data are encoded to Json.
   */
  override def encodeScala(value: Any): ScalaPbAny = {
    if (value == null) throw NullSerializationException
    value match {
      case javaPbAny: JavaPbAny   => ScalaPbAny.fromJavaProto(javaPbAny)
      case scalaPbAny: ScalaPbAny => scalaPbAny
      case bytes: Array[Byte]     => ScalaPbAny.fromJavaProto(JavaPbAny.pack(BytesValue.of(ByteString.copyFrom(bytes))))
      case other => ScalaPbAny.fromJavaProto(JsonSupport.encodeJson(other, lookupTypeHintWithVersion(other)))
    }
  }

  def encodeJavaToBytes(value: Any): akka.util.ByteString = {
    if (value == null) throw NullSerializationException
    val buf = JsonSupport.encodeToBytes(value, lookupTypeHintWithVersion(value)).asReadOnlyByteBuffer()
    akka.util.ByteString.fromByteBuffer(buf)
  }

  override def encodeJava(value: Any): JavaPbAny = {
    if (value == null) throw NullSerializationException
    value match {
      case javaPbAny: JavaPbAny   => javaPbAny
      case scalaPbAny: ScalaPbAny => ScalaPbAny.toJavaProto(scalaPbAny)
      case other                  => JsonSupport.encodeJson(other, lookupTypeHintWithVersion(other))
    }
  }

  private def lookupTypeHintWithVersion(value: Any): String =
    lookupTypeHint(value.getClass).currenTypeHintWithVersion

  private[akka] def lookupTypeHint(clz: Class[_]): TypeHint = {
    typeHints.computeIfAbsent(clz, computeTypeHint)
  }

  private[akka] def registerTypeHints(clz: Class[_]) = {
    lookupTypeHint(clz)
    if (clz.getAnnotation(classOf[JsonSubTypes]) != null) {
      //registering all subtypes
      clz
        .getAnnotation(classOf[JsonSubTypes])
        .value()
        .map(_.value())
        .foreach(lookupTypeHint)
    }
  }

  private def computeTypeHint(clz: Class[_]): TypeHint = {
    if (clz.getName.contains("java.lang")) {
      val typeHint = if (clz.isAssignableFrom(classOf[String])) {
        TypeHint("string", List("string", "java.lang.String"))
      } else if (clz.isAssignableFrom(classOf[lang.Integer])) {
        TypeHint("int", List("int", "java.lang.Integer"))
      } else if (clz.isAssignableFrom(classOf[lang.Long])) {
        TypeHint("long", List("long", "java.lang.Long"))
      } else if (clz.isAssignableFrom(classOf[lang.Boolean])) {
        TypeHint("boolean", List("boolean", "java.lang.Boolean"))
      } else if (clz.isAssignableFrom(classOf[lang.Double])) {
        TypeHint("double", List("double", "java.lang.Double"))
      } else if (clz.isAssignableFrom(classOf[lang.Float])) {
        TypeHint("float", List("float", "java.lang.Float"))
      } else if (clz.isAssignableFrom(classOf[lang.Character])) {
        TypeHint("char", List("char", "java.lang.Character"))
      } else if (clz.isAssignableFrom(classOf[lang.Byte])) {
        TypeHint("byte", List("byte", "java.lang.Byte"))
      } else if (clz.isAssignableFrom(classOf[lang.Short])) {
        TypeHint("short", List("short", "java.lang.Short"))
      } else {
        TypeHint(clz.getName, List(clz.getName))
      }
      typeHint.allTypeHints.foreach(className => addToReversedCache(clz, className))
      typeHint
    } else {
      val typeName = Option(clz.getAnnotation(classOf[TypeName]))
        .collect { case ann if ann.value().trim.nonEmpty => ann.value() }
        .getOrElse(clz.getName)

      val (version, supportedClassNames) = getVersionAndSupportedClassNames(clz)
      val typeNameWithVersion = typeName + (if (version == 0) "" else "#" + version)

      addToReversedCache(clz, typeName)
      supportedClassNames.foreach(className => addToReversedCache(clz, className))

      TypeHint(typeNameWithVersion, typeName :: supportedClassNames)
    }
  }

  private def addToReversedCache(clz: Class[_], typeName: String) = {
    reversedTypeHints.compute(
      typeName,
      (_, currentValue) => {
        if (currentValue eq null) {
          clz
        } else if (currentValue == clz) {
          currentValue
        } else {
          throw new IllegalStateException(
            "Collision with existing existing mapping " + currentValue + " -> " + typeName + ". The same type name can't be used for other class " + clz)
        }
      })
  }

  private def getVersionAndSupportedClassNames(clz: Class[_]): (Int, List[String]) = {
    Option(clz.getAnnotation(classOf[Migration]))
      .map(_.value())
      .map(migrationClass => migrationClass.getConstructor().newInstance())
      .map(migration =>
        (migration.currentVersion(), migration.supportedClassNames().asScala.toList)) //TODO what about TypeName
      .getOrElse((0, List.empty))
  }

  def typeUrlFor(clz: Class[_]): String = {
    if (clz == classOf[Array[Byte]]) {
      BytesPrimitive.fullName
    } else {
      AnySupport.JsonTypeUrlPrefix + lookupTypeHint(clz).currenTypeHintWithVersion
    }
  }

  def typeUrlsFor(clz: Class[_]): List[String] = {
    if (clz == classOf[Array[Byte]]) {
      List(BytesPrimitive.fullName)
    } else {
      lookupTypeHint(clz).allTypeHints.map(AnySupport.JsonTypeUrlPrefix + _)
    }
  }

  override def decodeMessage(value: ScalaPbAny): Any = {
    value
  }

  def decodeMessage[T](expectedType: Class[T], bytes: akka.util.ByteString): T = {
    // FIXME could we avoid the copy?
    JsonSupport.parseBytes(bytes.toArrayUnsafe(), expectedType)
  }

  private[akka] def removeVersion(typeName: String) = {
    typeName.split("#").head
  }
}

/**
 * Used in workflows where it is necessary to decode message directly to Java class for calls and transitions. This
 * behavior is not correct for other components (Action, Views) where e.g. subscription can't decode the payload to Java
 * class too early (typeUrl is used for the component logic). It must reuse the same cache as JsonMessageCodec.
 *
 * INTERNAL API
 */
@InternalApi
private[javasdk] class StrictJsonMessageCodec(delegate: JsonMessageCodec) extends MessageCodec {

  override def toString: String = s"StrictJsonMessageCodec -> $delegate"
  override def decodeMessage(value: ScalaPbAny): Any =
    if (AnySupport.isJsonTypeUrl(value.typeUrl)) {
      val typeName = delegate.removeVersion(AnySupport.stripJsonTypeUrlPrefix(value.typeUrl))
      val typeClass = delegate.reversedTypeHints.get(typeName)
      if (typeClass eq null) {
        throw new IllegalStateException(s"Cannot decode ${value.typeUrl} message type. Class mapping not found.")
      } else {
        JsonSupport.decodeJson(typeClass, value)
      }
    } else {
      value
    }

  override def encodeScala(value: Any): ScalaPbAny =
    delegate.encodeScala(value)

  override def encodeJava(value: Any): JavaPbAny =
    delegate.encodeJava(value)

  override def typeUrlFor(clz: Class[_]): String = delegate.typeUrlFor(clz)
}
