/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.spotify.scio.coders

import java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream}

import com.google.cloud.dataflow.sdk.coders.Coder.Context
import com.google.cloud.dataflow.sdk.coders.{AtomicCoder, Coder, CoderException}
import com.google.cloud.dataflow.sdk.util.VarInt
import com.google.common.io.ByteStreams
import com.twitter.chill._
import org.apache.avro.generic.GenericRecord
import org.apache.avro.specific.SpecificRecordBase

import scala.collection.convert.Wrappers.JIterableWrapper

private[scio] class KryoAtomicCoder extends AtomicCoder[Any] {

  @transient
  private lazy val kryo: Kryo = {
    val k = KryoSerializer.registered.newKryo()

    // java.lang.Iterable.asScala returns JIterableWrapper which causes problem.
    // Treat it as standard Iterable instead.
    k.register(classOf[JIterableWrapper[_]], new JIterableWrapperSerializer)

    k.forSubclass[SpecificRecordBase](new SpecificAvroSerializer)
    k.forSubclass[GenericRecord](new GenericAvroSerializer)

    k.forClass(new KVSerializer)
    // TODO:
    // InstantCoder
    // TimestampedValueCoder

    k
  }

  override def encode(value: Any, outStream: OutputStream, context: Context): Unit = {
    if (value == null) {
      throw new CoderException("cannot encode a null value")
    }
    if (context.isWholeStream) {
      val output = new Output(outStream)
      kryo.writeClassAndObject(output, value)
      output.flush()
    } else {
      val s = new ByteArrayOutputStream()
      val output = new Output(s)
      kryo.writeClassAndObject(output, value)
      output.flush()
      s.close()

      VarInt.encode(s.size(), outStream)
      outStream.write(s.toByteArray)
    }
  }

  override def decode(inStream: InputStream, context: Context): Any = {
    if (context.isWholeStream) {
      kryo.readClassAndObject(new Input(inStream))
    } else {
      val length = VarInt.decodeInt(inStream)
      if (length < 0) {
        throw new IOException("invalid length " + length)
      }

      val value = Array.ofDim[Byte](length)
      ByteStreams.readFully(inStream, value)
      kryo.readClassAndObject(new Input(value))
    }
  }

}

private[scio] object KryoAtomicCoder {
  def apply[T]: Coder[T] = (new KryoAtomicCoder).asInstanceOf[Coder[T]]
}
