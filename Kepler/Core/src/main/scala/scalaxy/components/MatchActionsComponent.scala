package scalaxy ; package plugin
//import common._
import language.existentials
import scala.tools.reflect.ToolBox

import pluginBase._
import components._

import scala.tools.nsc.Global

import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.typechecker.Contexts
import scala.tools.nsc.typechecker.Modes
//import scala.Predef._
//import scala.reflect._

import scala.reflect._

//import scala.tools.nsc.typechecker.Contexts._

object MatchActionsComponent {
  val runsAfter = List[String](
    "typer"
  )
  val runsBefore = List[String](
    "patmat"
  )
  val phaseName = "scalaxy-rewriter"
}

class MatchActionsComponent(val global: Global, val options: PluginOptions, val matchActionHolders: AnyRef*)
extends PluginComponent
   with Transform
   with TypingTransformers
   with Modes
   
   with WithOptions
   with PatternMatchers
   with MirrorConversions
   with SymbolHealers
{
  import global._
  import global.definitions._
  import gen._
  import CODE._
  import scala.tools.nsc.symtab.Flags._
  import typer.typed
  import analyzer.{SearchResult, ImplicitSearch, UnTyper}

  override val runsAfter = MatchActionsComponent.runsAfter
  override val runsBefore = MatchActionsComponent.runsBefore
  override val phaseName = MatchActionsComponent.phaseName

  override val patternUniv = runtime.universe
  override val candidateUniv = global
  
  import MatchActionDefinitions._
  
  val matchActions = {
    val filteredHolders = matchActionHolders.filter(_ != null)
    
    val rawMatchActions = filteredHolders.flatMap(holder => {
      val defs = getMatchActionDefinitions(holder)
      if (defs.isEmpty)
        sys.error("ERROR: no definition in holder " + holder)
        
      defs//.map({case (n, a) => (n, a.typeCheck(tb.typeCheck(_, _))) })
    })
    
    if (HacksAndWorkarounds.fixTypedExpressionsType) {
      val treeFixer = new ExprTreeFixer {
        val universe = patternUniv
      }
      for (MatchActionDefinition(n, _, _, m) <- rawMatchActions) {
        treeFixer.fixTypedExpression(
          n.toString,
          m.pattern.asInstanceOf[treeFixer.universe.Expr[Any]])
      }
    }
    
    //if (options.verbose)
    for (MatchActionDefinition(n, _, _, m) <- rawMatchActions) {
      println("Registered match action '" + n + "' with pattern : " + m.pattern.tree)
    }
    
    //println("Found " + rawMatchActions.size + " match actions in " + filteredHolders.size + " different holders")
    
    /*
    lazy val mirrorToolBox = runtime.universe.rootMirror.mkToolBox()
    //lazy val globalToolBox = global.mkToolBox()
    
    // TODO fix bugs that happen when this is true and/or false : 
    val typeCheckInMirrorSpace = true
    
    rawMatchActions.flatMap { 
      case (n, m) =>
        try {
          val converted =
            if (typeCheckInMirrorSpace) 
              mirrorToGlobal(mirrorToolBox.typeCheck(m.pattern.tree), EmptyBindings)
            else 
              globalToolBox.typeCheck(mirrorToGlobal(m.pattern.tree, EmptyBindings))
            
          println("Registered match action '" + n + "' = " + m)
          println("Converted pattern = " + converted)
          
          Some(n -> ConvertedMatchAction(converted, m))
        } catch { 
          case ex =>
            println("Failed to convert match action '" + n + "':\n\t" + ex + "\n\t" + m)
            ex.printStackTrace
            None
        }
    }
    */
    
    rawMatchActions.map(d => (d.name, d)).toMap
  }
  
  private def toTypedString(v: Any) = 
    v + Option(v).map(_ => ": " + v.getClass.getName + " <- " + v.getClass.getSuperclass.getSimpleName).getOrElse("")
                
  def newTransformer(unit: CompilationUnit) = new TypingTransformer(unit) {  
    override def transform(tree: Tree): Tree = {
      try {
        val sup = super.transform(tree)
        var expanded = sup
        
        for ((n, MatchActionDefinition(_, paramCount, typeParamCount, matchAction)) <- matchActions) {
          try {
            val bindings = 
              matchAndResolveTreeBindings(matchAction.pattern.tree.asInstanceOf[patternUniv.Tree], expanded.asInstanceOf[candidateUniv.Tree])
              
            //println("Bindings for '" + n + "':\n\t" + (bindings.nameBindings ++ bindings.typeBindings).mkString("\n\t"))
            
            //if (bindings.nameBindings.size < paramCount
            //    /* || TODO check type params
            //    bindings.typeBindings.size < typeParamCount*/) 
            //{
            //  println("NOT ENOUGH BINDINGS (expected " + paramCount + " params and " + typeParamCount + " type params), skipping " + n + " for tree:\n" + tree)
            //} else 
            matchAction match  {
              case r @ Replacement(_, _) =>
                val replacement =
                  mirrorToGlobal(runtime.universe)(r.replacement.tree, bindings)
                //println("Replacement '" + n + "':\n\t" + replacement.toString.replaceAll("\n", "\n\t"))
                expanded = replacement
              case MatchWarning(_, message) =>
                unit.warning(tree.pos, message)
              case MatchError(_, message) =>
                unit.error(tree.pos, message)
              case ConditionalAction(_, when, thenMatch) =>
                val treesToTest: List[scala.reflect.runtime.universe.Tree] = 
                  when.toList.map(n => { 
                    globalToMirror(
                      scala.reflect.runtime.universe
                    )(
                      bindings.nameBindings(n.toString).asInstanceOf[global.Tree]
                    )
                  })
                
                if (thenMatch.isDefinedAt(treesToTest)) {
                  thenMatch.apply(treesToTest) match {
                    case r: ReplaceBy[_] =>
                      val replacement = mirrorToGlobal(
                        scala.reflect.runtime.universe
                      )(
                        r.replacement.tree, bindings
                      )
                      //println("Replace by '" + n + "':\n\t" + replacement.toString.replaceAll("\n", "\n\t"))
                      expanded = replacement
                    case Warning(message) =>
                      unit.warning(tree.pos, message)
                    case Error(message) =>
                      unit.error(tree.pos, message)
                    case null =>
                  }
                }
            }
          } catch { 
            case NoTypeMatchException(expected, found, msg, depth, insideExpected, insideFound) =>
              if (false)//depth > 0) 
              {
                println("TYPE ERROR: in replacement '" + n + "' at " + tree.pos + " : " + msg +
                  " (")
                println("\texpected = " + toTypedString(expected))
                println("\tfound = " + toTypedString(found))
                println("\tinside expected = " + insideExpected)
                println("\tinside found = " + insideFound) 
                println(")")
              }
            case NoTreeMatchException(expected, found, msg, depth) =>
              if (false)//depth > 0) 
              {
                println("TREE ERROR: in replacement '" + n + "' at " + tree.pos + " : " + msg +
                  " (\n\texpected = " + toTypedString(expected) + 
                  ",\n\tfound = " + toTypedString(found) + "\n)"
                )
                println("Tree was " + tree)
                println("Match action was " + matchAction)
              }
          }
        }
      
        if (expanded eq sup) {
          sup
        } else {
          val expectedTpe = tree.tpe.dealias.deconst.normalize
          
          val tpe = expanded.tpe
          //eraseTypes(expanded)
          //expanded.tpe = null
          
          if (HacksAndWorkarounds.healSymbols) {
              expanded = healSymbols(unit, currentOwner, expanded, expectedTpe)
          }
          
          expanded = typer.typed(expanded, EXPRmode, expectedTpe)
          
          //println()
          //println("FINAL EXPANSION = \n" + nodeToString(expanded))
          //println()
          
          if (expanded.tpe == null || expanded.tpe == NoType)
            expanded.tpe = tpe
          expanded
        }
      } catch { case ex =>
        println(ex)
        ex.printStackTrace
        println("Error while trying to replace " + tree + " : " + ex)
        tree
      }
    }
  }
}