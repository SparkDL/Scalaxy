// Author: Olivier Chafik (http://ochafik.com)
package scalaxy.loops

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.symtab.Flags

/**
 *  To understand / reproduce this, you should use paulp's :power mode in the scala console:
 *
 *  scala
 *  > :power
 *  > :phase parser // will show us ASTs just after parsing
 *  > val Some(List(ast)) = intp.parse("@public def str = self.toString")
 *  > nodeToString(ast)
 *  > val DefDef(mods, name, tparams, vparamss, tpt, rhs) = ast // play with extractors to explore the tree and its properties.
 */
object LoopsComponent {
  val phaseName = "scalaxy-loops"
}
class LoopsComponent(
  val global: Global, runAfter: String = "typer")
    extends PluginComponent
    with StreamOps {
  import global._
  import definitions._
  import Flags._

  override val phaseName = LoopsComponent.phaseName

  override val runsRightAfter = Option(runAfter)
  override val runsAfter = runsRightAfter.toList
  override val runsBefore = List("patmat")

  override def typed(tree: Tree, tpe: Type) =
    typer.typed(tree, tpe)

  override def newPhase(prev: Phase) = new StdPhase(prev) {
    def apply(unit: CompilationUnit) {
      unit.body = new Transformer {

        override def transform(tree: Tree) = tree match {
          case SomeStream(stream) =>
            // println(s"source = $source")
            reporter.info(tree.pos, s"stream = $stream", force = true)
            val result = stream.emitStream(n => unit.fresh.newName(n): TermName, transform(_))
            println(result)

            typer.typed {
              result
            }

          case _ =>
            super.transform(tree)
        }
      } transform unit.body
    }
  }
}