/*
 * ScalaCL - putting Scala on the GPU with JavaCL / OpenCL
 * http://scalacl.googlecode.com/
 *
 * Copyright (c) 2009-2013, Olivier Chafik (http://ochafik.com/)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Olivier Chafik nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY OLIVIER CHAFIK AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package scalaxy.components

import scala.reflect.ClassTag

object FlatCodes {
  def EmptyFlatCode[T] = FlatCode[T](Seq(), Seq(), Seq())

  def merge[T](fcs: FlatCode[T]*)(f: Seq[T] => Seq[T]): FlatCode[T] =
    fcs.reduceLeft(_ ++ _).mapValues(f)

}

case class FlatCode[T](
    /// External functions that are referenced by statements and / or values 
    outerDefinitions: Seq[T] = Seq(),
    /// List of variable definitions and other instructions (if statements, do / while loops...)
    statements: Seq[T] = Seq(),
    /// Final values of the code in a "flattened tuple" style
    values: Seq[T] = Seq()) {
  def map[V](f: T => V): FlatCode[V] =
    FlatCode[V](
      outerDefinitions = outerDefinitions.map(f),
      statements = statements.map(f),
      values = values.map(f)
    )

  def transform(f: Seq[T] => Seq[T]): FlatCode[T] =
    FlatCode[T](
      outerDefinitions = f(outerDefinitions),
      statements = f(statements),
      values = f(values)
    )

  def mapEachValue(f: T => Seq[T]): FlatCode[T] =
    copy(values = values.flatMap(f))

  def mapValues(f: Seq[T] => Seq[T]): FlatCode[T] =
    copy(values = f(values))

  def ++(fc: FlatCode[T]) =
    FlatCode(outerDefinitions ++ fc.outerDefinitions, statements ++ fc.statements, values ++ fc.values)

  def >>(fc: FlatCode[T]) =
    FlatCode(outerDefinitions ++ fc.outerDefinitions, statements ++ fc.statements ++ values, fc.values)

  def noValues =
    FlatCode(outerDefinitions, statements ++ values, Seq())

  def addOuters(outerDefs: Seq[T]) =
    copy(outerDefinitions = outerDefinitions ++ outerDefs)

  def addStatements(stats: Seq[T]) =
    copy(statements = statements ++ stats)

  def printDebug(name: String = "") = {
    def pt(seq: Seq[T]) = println("\t" + seq.map(_.toString.replaceAll("\n", "\n\t")).mkString("\n\t"))
    println("FlatCode(" + name + "):")
    pt(outerDefinitions)
    println("\t--")
    pt(statements)
    println("\t--")
    pt(values)
  }

  def flatMap[V: ClassTag](f: T => FlatCode[V]): FlatCode[V] =
    {
      val Array(convDefs, convStats, convVals) =
        Array(outerDefinitions, statements, values).map(_ map f)

      val outerDefinitions2 =
        Seq(convDefs, convStats, convVals).flatMap(_.flatMap(_.outerDefinitions)).distinct.toArray.sortBy(_.toString.startsWith("#"))

      val statements2 =
        Seq(convStats, convVals).flatMap(_.flatMap(_.statements))

      val values2: Seq[V] =
        convVals.flatMap(_.values)

      FlatCode[V](
        outerDefinitions2,
        statements2,
        values2
      )
    }
}

