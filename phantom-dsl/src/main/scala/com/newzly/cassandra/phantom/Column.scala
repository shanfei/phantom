/*
 * Copyright 2013 newzly ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com
package newzly
package cassandra
package phantom

import java.util.{ Map => JMap }
import scala.collection.breakOut
import scala.collection.JavaConverters._

import com.datastax.driver.core.Row

import net.liftweb.json.Formats
import net.liftweb.json.{
  DefaultFormats,
  Extraction,
  JsonAST,
  JsonDSL,
  JsonParser
}
import net.liftweb.json.Serialization.write
import scala.reflect.ClassTag


trait Helpers {
  private[cassandra] implicit class RichSeq[T](val l: Seq[T]) {
    final def toOption: Option[Seq[T]] = if (l.isEmpty) None else Some(l)
  }
}

trait AbstractColumn[T] extends CassandraWrites[T] {

  type ValueType

  lazy val name: String = getClass.getSimpleName.replaceAll("\\$+", "").replaceAll("(anonfun\\d+.+\\d+)|", "")

  def apply(r: Row): ValueType

  def optional(r: Row): Option[T]
}

trait Column[T] extends AbstractColumn[T] {

  type ValueType = T

  override def apply(r: Row): T =
    optional(r).getOrElse(throw new Exception(s"can't extract required value for column '$name'"))
}

trait OptionalColumn[T] extends AbstractColumn[T] {

  type ValueType = Option[T]

  override def apply(r: Row) = optional(r)
}

class OptionalPrimitiveColumn[T: CassandraPrimitive] extends OptionalColumn[T] {

  def toCType(v: T): AnyRef = CassandraPrimitive[T].toCType(v)

  def optional(r: Row): Option[T] = implicitly[CassandraPrimitive[T]].fromRow(r, name)
}

class PrimitiveColumn[RR: CassandraPrimitive] extends Column[RR] {

  def toCType(v: RR): AnyRef = CassandraPrimitive[RR].toCType(v)

  def optional(r: Row): Option[RR] =
    implicitly[CassandraPrimitive[RR]].fromRow(r, name)
}

class JsonTypeColumn[RR: Manifest] extends Column[RR] {

  val mf = implicitly[Manifest[RR]]
  implicit val formats = DefaultFormats
  def toCType(v: RR): AnyRef = write(v.asInstanceOf[AnyRef])

  def optional(r: Row): Option[RR] = {
    Option(r.getString(name)).flatMap(e => JsonParser.parse(e).extractOpt[RR](DefaultFormats, mf))
  }
}

class EnumColumn[EnumType <: Enumeration](enum: EnumType) extends Column[EnumType#Value] {

  def toCType(v: EnumType#Value): AnyRef = v.toString

  def optional(r: Row): Option[EnumType#Value] =
    Option(r.getString(name)).flatMap(s => enum.values.find(_.toString == s))

}

class SetColumn[RR:CassandraPrimitive] extends Column[Set[RR]] {

  def toCType(values: Set[RR]): AnyRef = values.map(CassandraPrimitive[RR].toCType).asJava

  override def apply(r: Row): Set[RR] = {
    optional(r).getOrElse(Set.empty)
  }

  def optional(r: Row): Option[Set[RR]] = {

    val i = implicitly[CassandraPrimitive[RR]]
    Option(r.getSet(name, i.cls)).map(_.asScala.map(e => i.fromCType(e.asInstanceOf[AnyRef])).toSet)
  }
}

class SeqColumnNew[S <: Seq[RR],RR:CassandraPrimitive] extends Column[Seq[RR]] {

  def toCType(values: Seq[RR]): AnyRef = values.map(CassandraPrimitive[RR].toCType).asJava

  override def apply(r: Row): Seq[RR] = {
    optional(r).getOrElse(Seq.empty)
  }

  def optional(r: Row): Option[Seq[RR]] = {

    val i = implicitly[CassandraPrimitive[RR]]
    Option(r.getList(name, i.cls)).map(_.asScala.map(e => i.fromCType(e.asInstanceOf[AnyRef])).toIndexedSeq)
  }
}

class SeqColumn[RR: CassandraPrimitive] extends Column[Seq[RR]] {

  def toCType(values: Seq[RR]): AnyRef = values.map(CassandraPrimitive[RR].toCType).asJava

  override def apply(r: Row): Seq[RR] = {
    optional(r).getOrElse(Seq.empty)
  }

  def optional(r: Row): Option[Seq[RR]] = {
    val i = implicitly[CassandraPrimitive[RR]]
    Option(r.getList(name, i.cls)).map(_.asScala.map(e => i.fromCType(e.asInstanceOf[AnyRef])).toIndexedSeq)
  }
}

class MapColumn[K: CassandraPrimitive, V: CassandraPrimitive] extends Column[Map[K, V]] {

  def toCType(values: Map[K, V]): JMap[AnyRef, AnyRef] = values.map {
    case (k, v) => CassandraPrimitive[K].toCType(k) -> CassandraPrimitive[V].toCType(v)
  }.asJava

  override def apply(r: Row): Map[K, V] = {
    optional(r).getOrElse(Map.empty)
  }

  def optional(r: Row): Option[Map[K, V]] = {
    val ki = implicitly[CassandraPrimitive[K]]
    val vi = implicitly[CassandraPrimitive[V]]
    Option(r.getMap(name, ki.cls, vi.cls)).map(_.asScala.map {
      case (k, v) =>
        ki.fromCType(k.asInstanceOf[AnyRef]) -> vi.fromCType(v.asInstanceOf[AnyRef])
    }(breakOut) toMap)
  }
}

class JsonTypeSeqColumn[RR: Manifest] extends Column[Seq[RR]] with Helpers {

  def toCType(values: Seq[RR]): AnyRef = values.map(Extraction.decompose(_)(DefaultFormats))(breakOut).asJava

  override def apply(r: Row): Seq[RR] = {
    optional(r).getOrElse(Seq.empty)
  }

  def optional(r: Row): Option[Seq[RR]] = {
    r.getList(name, classOf[String]).asScala.flatMap(
      JsonParser.parse(_).extractOpt[RR](
        DefaultFormats,
        implicitly[Manifest[RR]]
      ))(breakOut).toSeq.toOption
  }
}