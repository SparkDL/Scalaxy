// Author: Olivier Chafik (http://ochafik.com)
package scalaxy.streams

import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.transform.TypingTransformers

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
object StreamsComponent {
  val phaseName = "scalaxy-streams"
}
class StreamsComponent(
  val global: Global, runAfter: String = "typer")
    extends PluginComponent
    with StreamTransforms
    with TypingTransformers {
  import global._
  import definitions._
  import Flags._

  override val phaseName = StreamsComponent.phaseName

  override val runsRightAfter = Option(runAfter)
  override val runsAfter = runsRightAfter.toList
  override val runsBefore = List("patmat")

  override def newPhase(prev: Phase) = new StdPhase(prev) {
    def apply(unit: CompilationUnit) {
      val transformer = new TypingTransformer(unit) {

        override def transform(tree: Tree) = tree match {
          case SomeStream(stream) =>
            reporter.info(tree.pos, impl.optimizedStreamMessage(stream.describe()), force = true)
            val result = {
              stream
                .emitStream(
                  n => unit.fresh.newName(n): TermName,
                  transform(_),
                  localTyper.typed(_))
                .compose(localTyper.typed(_))
            }
            result

          case _ =>
            super.transform(tree)
        }
      }

      unit.body = transformer transform unit.body
    }
  }
}