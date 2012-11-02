package scalaxy ; package plugin
//import common._
import pluginBase._
import components._

import scala.tools.nsc.Global

import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.typechecker.Contexts
import scala.tools.nsc.typechecker.Modes
import scala.Predef._

trait SymbolHealers
extends TypingTransformers
   with Modes
{
  this: PluginComponent =>

  import global._
  import global.definitions._
  import scala.tools.nsc.symtab.Flags._

  def healSymbols(unit: CompilationUnit, rootOwner: Symbol, root: Tree, expectedTpe: Type): Tree = {
    new Transformer {
      var syms = new collection.mutable.HashMap[String, Symbol]()
      currentOwner = rootOwner

      def subSyms[V](v: => V): V = {
        val oldSyms = syms
        syms = new collection.mutable.HashMap[String, Symbol]()
        syms ++= oldSyms
        try {
          v
        } finally {
          syms = oldSyms
        }
      }
      override def transform(tree: Tree) = {
        try {
          def transformValDef(vd: ValDef) = {
            val ValDef(mods, name, tpt, rhs) = vd
            //println("Found valdef " + vd)
            val sym = (
              if (mods.hasFlag(MUTABLE))
                currentOwner.newVariable(name)
              else
                currentOwner.newValue(name)
            ).setFlag(mods.flags)

            tree.setSymbol(sym)

            syms(name.toString) = sym

            atOwner(sym) {
              transform(tpt)
              
              //println("syms = " + syms)
              //println("transforming rhs = " + rhs + " (" + nodeToString(rhs) + ")")
              transform(rhs)
              //println("\trhs = " + nodeToString(rhs))
            }

            typer.typed(rhs)

            var tpe = rhs.tpe
            if (tpe.isInstanceOf[ConstantType])
              tpe = tpe.widen

            sym.setInfo(Option(tpe).getOrElse(NoType))

            val rep = ValDef(mods, name, TypeTree(tpe), rhs)
            rep.symbol = sym
            rep
          }

          tree match {
            case (_: Block) | (_: ClassDef) =>
              //println("Found block or class " + tree)
              subSyms {
                super.transform(tree)
              }

            case Function(vparams, body) =>
              val sym = currentOwner.newAnonymousFunctionValue(NoPosition)
              tree.setSymbol(sym)

              atOwner(sym) {
                subSyms {
                  vparams.foreach(transformValDef _)
                  transform(body)
                }
              }
              tree

            case Ident(n) =>
              // if tree.symbol.owner.isNestedIn(rootOwner)
              for (s <- syms.get(n.toString))
                tree.setSymbol(s)
              tree

            case vd: ValDef =>
              transformValDef(vd)

            case _ =>
              super.transform(tree)
          }
        } catch { case ex: Throwable =>
          println("ERROR while assigning missing symbols to " + tree + ": " + tree.getClass.getName + " : " + ex + "\n\t" + nodeToString(tree))
          ex.printStackTrace
          throw ex
        }
      }
    }.transform(root)
  }
}