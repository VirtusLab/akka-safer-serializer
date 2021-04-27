package org.virtuslab.akkasaferserializer

import akka.actor
import akka.actor.typed.{ActorRef, ActorRefResolver, ActorSystem}
import akka.serialization.Serialization
import akka.stream.{SinkRef, SourceRef, StreamRefResolver}
import io.bullet.borer.Codec

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.time.OffsetDateTime

object StandardCodecs {

  implicit def sinkRefCodec[T](implicit system: actor.ActorSystem = serializationSystem): Codec[SinkRef[T]] = {
    val resolver = StreamRefResolver(ActorSystem.wrap(system))

    Codec.bimap[String, SinkRef[T]](resolver.toSerializationFormat(_: SinkRef[T]), resolver.resolveSinkRef)
  }

  implicit def sourceRefCodec[T](implicit system: actor.ActorSystem = serializationSystem): Codec[SourceRef[T]] = {
    val resolver = StreamRefResolver(ActorSystem.wrap(system))

    Codec.bimap[String, SourceRef[T]](resolver.toSerializationFormat(_: SourceRef[T]), resolver.resolveSourceRef)
  }

  private def serializationSystem: actor.ActorSystem = Serialization.getCurrentTransportInformation().system

  implicit val offsetDateTimeCodec: Codec[OffsetDateTime] =
    Codec.bimap[Array[Byte], OffsetDateTime](
      serializeSerializable[OffsetDateTime],
      deserializeSerializable[OffsetDateTime])

  private def serializeSerializable[T <: java.io.Serializable](ser: T): Array[Byte] = {
    val os = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(os)
    oos.writeObject(ser)
    oos.close()
    os.toByteArray
  }

  private def deserializeSerializable[T <: java.io.Serializable](buffer: Array[Byte]): T = {
    val ois = new ObjectInputStream(new ByteArrayInputStream(buffer))
    val ser = ois.readObject.asInstanceOf[T]
    ois.close()
    ser
  }
}
