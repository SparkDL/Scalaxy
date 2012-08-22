package scalaxy; package components

import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.ast.TreeDSL
import scala.tools.nsc.transform.TypingTransformers
import Function.tupled

import HacksAndWorkarounds._

trait PatternMatchers      
extends TypingTransformers
//   with MirrorConversions
{
  this: PluginComponent =>

  import scala.tools.nsc.symtab.Flags._

  import scala.reflect._
  
  val patternUniv: api.Universe 
  val candidateUniv: api.Universe with scala.reflect.internal.Importers
  
  case class Bindings(
    nameBindings: Map[String, candidateUniv.Tree] = Map(), 
    typeBindings: Map[patternUniv.Type, candidateUniv.Type] = Map()
  ) {
    def getType(t: patternUniv.Type): Option[candidateUniv.Type] =
      Option(t).flatMap(typeBindings.get(_))
    
    def bindName(n: patternUniv.Name, v: candidateUniv.Tree) =
      copy(nameBindings = nameBindings + (n.toString -> v))
     
    def bindType(t: patternUniv.Type, t2: candidateUniv.Type) =
      copy(typeBindings = typeBindings + (t -> t2))
      
    def ++(b: Bindings) =
      Bindings(
        nameBindings ++ b.nameBindings, typeBindings ++ b.typeBindings
      )
      
    def convertToExpected[T](v: Any) = v.asInstanceOf[T]
    
    def apply(replacement: patternUniv.Tree): candidateUniv.Tree = 
    {
      //val toto = candidateUniv.asInstanceOf[scala.reflect.internal.Importers]
      val importer = new candidateUniv.StandardImporter {
        val from = patternUniv.asInstanceOf[scala.reflect.internal.SymbolTable]
        override def importTree(tree: from.Tree) = convertToExpected {//: candidateUniv.Tree = {
          tree match {
            case from.Ident(n) =>
              nameBindings.get(n.toString).
              getOrElse(super.importTree(tree))
              //{ val imp = candidateUniv.Ident(in) ; imp.tpe = importType(tree.tpe) ; imp })
            case _ =>
              super.importTree(tree)
          }
        }//.asInstanceOf[candidateUniv.Tree]//
        //*
        override def importType(tpe: from.Type) = convertToExpected {
          if (tpe == null) {
            null
          } else {
            //val it = resolveType(candidateUniv)(super.importType(tpe)).asInstanceOf[candidateUniv.Type]
            //getType(it.asInstanceOf[patternUniv.Type]).getOrElse(it).asInstanceOf[candidateUniv.Type]
            
            //var it = super.importType(tpe)
            //it = resolveType(candidateUniv)(it)
            getType(resolveType(patternUniv)(tpe.asInstanceOf[patternUniv.Type])).
            getOrElse(super.importType(tpe))
            //.asInstanceOf[candidateUniv.Type]
          }
        }//*/
      }
      importer.importTree(replacement.asInstanceOf[importer.from.Tree])
    }
  }
  
  def combine(a: Bindings, b: Bindings) = a ++ b
    
  implicit def t2pt(tp: api.Types#Type) = tp.asInstanceOf[PlasticType]
  type PlasticType = {
    def dealias: api.Types#Type
    def deconst: api.Types#Type
    def normalize: api.Types#Type
    //def widen: WidenableType
  }
  
  def normalize(u: api.Universe)(tpe: u.Type) = {
    //tpe.dealias.deconst.normalize
    tpe.
    deconst.
    dealias.
    normalize.
    asInstanceOf[u.Type]
  }
    
  def resolveType(u: api.Universe)(tpe: u.Type): u.Type = 
      Option(tpe).map(normalize(u)(_)).map({
        case u.ThisType(sym) =>
          sym.asTypeSymbol.asType
        case tt @ u.SingleType(pre, sym) if !sym.isPackage =>
          try {
            val t = sym.asTypeSymbol.asType
            if (t != null && t != candidateUniv.NoType)
              t
            else
              tt
          } catch { case ex =>
            // TODO report to Eugene
            // ex.printStackTrace
            tt
          }
        case tt =>
          tt
      }).orNull
      
  def matchAndResolveTreeBindings(reps: List[(patternUniv.Tree, candidateUniv.Tree)], depth: Int)(implicit internalDefs: InternalDefs): Bindings = 
  {
    if (reps.isEmpty)
      EmptyBindings
    else
      reps.map({ case (a, b) => 
        matchAndResolveTreeBindings(a, b, depth)
      }).reduceLeft(_ ++ _)
  }
  
  // Throws lots of exceptions : NoTreeMatchException and NoTypeMatchException
  def matchAndResolveTypeBindings(reps: List[(patternUniv.Type, candidateUniv.Type)], depth: Int)(implicit internalDefs: InternalDefs): Bindings = 
  {
    if (reps.isEmpty)
      EmptyBindings
    else
      reps.map({ case (a, b) => 
        matchAndResolveTypeBindings(a, b, depth)
      }).reduceLeft(_ ++ _)
  }
  
  type InternalDefs = Set[patternUniv.Name]
  
  def getNamesDefinedIn(u: api.Universe)(stats: List[u.Tree]): Set[u.Name] =
    stats.collect { case u.ValDef(_, name, _, _) => name: u.Name } toSet
  
  case class NoTreeMatchException(expected: Any, found: Any, msg: String, depth: Int)
  extends RuntimeException(msg)
    
  case class NoTypeMatchException(expected: Any, found: Any, msg: String, depth: Int, insideExpected: AnyRef = null, insideFound: AnyRef = null)
  extends RuntimeException(msg)
    
  def isNoType(u: api.Universe)(t: u.Type) =
    t == null || 
    t == u.NoType || 
    t == u.NoPrefix ||
    t == u.UnitTpe || {
      val s = t.toString
      s == "<notype>" || s == "scala.this.Unit"
    }
    
  def clstr(v: AnyRef) = 
    if (v == null)
      "<null>"
    else
      v.getClass.getName + " <- " + v.getClass.getSuperclass.getName
    
  def types(u: api.Universe)(syms: List[u.Symbol]) = 
    syms.map(_.asTypeSymbol.asType)
          
  def zipTypes(syms1: List[patternUniv.Symbol], syms2: List[candidateUniv.Symbol]) = 
    types(patternUniv)(syms1).zip(types(candidateUniv)(syms2))
          
  def isTypeParameter(t: patternUniv.Type) = {
    t != null && {
      type PlasticSymbol = {
        def isTypeParameter: Boolean
      }
      
      val s = t.typeSymbol
      
      {
        try {
          s != null &&
          s.asInstanceOf[PlasticSymbol].isTypeParameter
        } catch { case _ => 
          false // TODO report to Eugene:
          // scala.NotImplementedError: an implementation is missing
          // at scala.Predef$.$qmark$qmark$qmark(Predef.scala:235)
          // at scala.reflect_compat$class.mirror(compat.scala:20)
          // at scala.reflect.package$.mirror$lzycompute(package.scala:3)
          // at scala.reflect.package$.mirror(package.scala:3)
          // at scalaxy.components.PatternMatchers$class.isTypeParameter(PatternMatchers.scala:168)
        }
      } /*||
      TypeVars.isTypeVar(mirror)(t.asInstanceOf[mirror.Type])*/
    }
  } 
    
  def matchAndResolveTypeBindings(
    pattern0: patternUniv.Type, 
    tree0: candidateUniv.Type, 
    depth: Int = 0, 
    strict: Boolean = false
  )(
    implicit internalDefs: InternalDefs = Set()
  ): Bindings = 
  {
    import candidateUniv._
    
    lazy val EmptyBindings = Bindings()
  
    val pattern = resolveType(patternUniv)(pattern0)
    val tree = resolveType(candidateUniv)(tree0)
    
    //lazy val desc = "(" + pattern + ": " + clstr(pattern) + " vs. " + tree + ": " + clstr(tree) + ")"
    
    if (workAroundNullPatternTypes &&
        tree == null && pattern != null) {
      throw NoTypeMatchException(pattern0, tree0, "Type kind matching failed (" + pattern + " vs. " + tree + ")", depth)
    } 
    else
    if (pattern != null && tree != null && pattern.kind != tree.kind) {
      throw NoTypeMatchException(pattern0, tree0, "Type kind matching failed (" + pattern.kind + " vs. " + tree.kind + ")", depth)
    }
    else
    if (pattern != null && pattern.typeSymbol != null &&
        tree != null && tree.typeSymbol != null &&
        pattern.typeSymbol.kind != tree.typeSymbol.kind) {
      throw NoTypeMatchException(pattern0, tree0, "Type symbol kind matching failed (" + pattern.typeSymbol.kind + " vs. " + tree.typeSymbol.kind + ")", depth)
    }
    else
    {
      val ret = (pattern, tree) match {
        // TODO remove null acceptance once macro typechecker is fixed !
        case (_, _)
        if pattern == null && workAroundNullPatternTypes => 
          //println("TYPE MATCH null expected type")
          EmptyBindings
          
        case (patternUniv.NoType, candidateUniv.NoType) => 
          //println("TYPE MATCH NoType")
          EmptyBindings
          
        case (patternUniv.NoPrefix, candidateUniv.NoPrefix) => 
          //println("TYPE MATCH NoPrefix")
          EmptyBindings
          
        case (patternUniv.UnitTpe, candidateUniv.UnitTpe) => 
          //println("TYPE MATCH UnitTpe")
          EmptyBindings
          
        case (_, _) if isTypeParameter(pattern) =>
          //println("TYPE MATCH type param")
          Bindings(Map(), Map(pattern -> tree))
          
        //case (_, _) if candNoType && !patNoType =>
        //  throw NoTypeMatchException(pattern0, tree0, "Type matching failed", depth)
        
        // TODO support refined types again:
        //case (patternUniv.RefinedType(parents, decls), RefinedType(parents2, decls2)) =>
        //  println("TYPE MATCH refined type")
        //  EmptyBindings
          
        case (patternUniv.TypeBounds(lo, hi), TypeBounds(lo2, hi2)) =>
          //println("TYPE MATCH type bounds")
          matchAndResolveTypeBindings(List((lo, lo2), (hi, hi2)), depth + 1)
          
        case (patternUniv.MethodType(paramtypes, result), MethodType(paramtypes2, result2)) =>
          //println("TYPE MATCH method")
          matchAndResolveTypeBindings((result, result2) :: zipTypes(paramtypes, paramtypes2), depth + 1)
          
        case (patternUniv.NullaryMethodType(result), NullaryMethodType(result2)) =>
          //println("TYPE MATCH nullary method")
          matchAndResolveTypeBindings(result, result2, depth + 1)
          
        case (patternUniv.PolyType(tparams, result), PolyType(tparams2, result2)) =>
          //println("TYPE MATCH poly")
          matchAndResolveTypeBindings((result, result2):: zipTypes(tparams, tparams2), depth + 1)
          
        case (patternUniv.ExistentialType(tparams, result), ExistentialType(tparams2, result2)) =>
          //println("TYPE MATCH existential")
          matchAndResolveTypeBindings((result, result2) :: zipTypes(tparams, tparams2), depth + 1)
        
        case (patternUniv.TypeRef(pre, sym, args), TypeRef(pre2, sym2, args2)) 
        if args.size == args2.size &&
           //sym.kind == sym2.kind &&
           sym != null && sym2 != null &&
           sym.name.toString == sym2.name.toString //&&
           //pattern.typeSymbol != null && tree.typeSymbol != null &&
           //pattern.typeSymbol.kind == tree.typeSymbol.kind 
        =>
          //println("TYPE MATCH typeref " + desc)
          matchAndResolveTypeBindings(pre, pre2, depth + 1) ++ 
          matchAndResolveTypeBindings(args.zip(args2), depth + 1)
        
        case _ =>
          if (Option(pattern).toString == Option(tree).toString) {
            //println("WARNING: Dumb string type matching of " + pattern + " vs. " + tree)
            EmptyBindings
          } else {
            //if (depth > 0)
            //  println("TYPE MISMATCH \n\texpected = " + pattern0 + "\n\t\t" + Option(pattern0).map(_.getClass.getName) + "\n\tfound = " + tree0 + "\n\t\t" + Option(tree0).map(_.getClass.getName))
            throw NoTypeMatchException(pattern0, tree0, "Type matching failed", depth)
          }
      }
      
      //println("Successfully bound " + pattern + " vs. " + tree)
      //if (pattern != null && tree != null)
      //  ret.bindType(pattern, tree)
      //else
      if (false) {
        println("Successfully bound types (depth " + depth + "):")
        println("\ttype pattern = " + pattern + ": " + clstr(pattern))// + "; kind = " + Option(pattern).map(_.typeSymbol.kind))
        println("\ttype found = " + tree + ": " + clstr(tree))
      }
      ret
    }
  }
  
  val EmptyBindings = Bindings()
  
  def matchAndResolveTreeBindings(pattern: patternUniv.Tree, tree: candidateUniv.Tree, depth: Int = 0)(implicit internalDefs: InternalDefs = Set()): Bindings = 
  {
    val patternType = getOrFixType(patternUniv)(pattern)
    val candidateType = getOrFixType(candidateUniv)(tree)
    
    val typeBindings = try {
      matchAndResolveTypeBindings(patternType, candidateType, depth)
    } catch { 
      case ex: NoTypeMatchException =>
        throw ex.copy(insideExpected = pattern, insideFound = tree)
    }
    //if (depth > 0) 
    //{
    //  println("Going down in trees (depth " + depth + "):")
    //  println("\tpattern = " + pattern + ": " + patternType + " (" + pattern.getClass.getName + ", " + clstr(patternType) + ")")
    //  println("\tfound = " + tree + ": " + candidateType + " (" + tree.getClass.getName + ", " + clstr(candidateType) + ")")
    //  println("\ttypeBindings = " + typeBindings)
    //}
    
    //lazy val desc = "(" + pattern + ": " + clstr(pattern) + " vs. " + tree + ": " + clstr(tree) + ")"
    
    typeBindings ++ {
      val ret = (pattern, tree) match {
        case (_, _) if pattern.isEmpty && tree.isEmpty =>
          //println("MATCH empty")
          EmptyBindings
          
        case (patternUniv.This(_), candidateUniv.This(_)) =>
          //println("MATCH this")
          EmptyBindings
          
        case (patternUniv.Literal(patternUniv.Constant(a)), candidateUniv.Literal(candidateUniv.Constant(a2))) 
        if a == a2 =>
          //println("MATCH literals")
          EmptyBindings
          
        case (patternUniv.Ident(n), _) =>
          if (internalDefs.contains(n)) {
            //println("MATCH internal def")
            EmptyBindings
          } else {/*tree match {
            case candidateUniv.Ident(nn) if n.toString == nn.toString =>
              EmptyBindings
            case _ =>*/
              //println("GOT BINDING " + pattern + " -> " + tree + " (tree is " + tree.getClass.getName + ")")
            //println("MATCH ident")
            Bindings(Map(n.toString -> tree), Map())
          }
            
        case (patternUniv.ValDef(mods, name, tpt, rhs), candidateUniv.ValDef(mods2, name2, tpt2, rhs2))
        if mods.flags == mods2.flags =>
          //println("MATCH val")
          val r = matchAndResolveTreeBindings(
            List((rhs, rhs2), (tpt, tpt2)), depth + 1
          )(
            internalDefs + name
          )
            
          if (name == name2)
            r
          else
            r.bindName(name, candidateUniv.Ident(name2))
        
        case (patternUniv.Function(vparams, body), candidateUniv.Function(vparams2, body2)) =>
          //println("MATCH function")
          matchAndResolveTreeBindings(
            (body, body2) :: vparams.zip(vparams2), depth + 1
          )(
            internalDefs ++ vparams.map(_.name)
          )
          
        case (patternUniv.TypeApply(fun, args), candidateUniv.TypeApply(fun2, args2)) =>
          //println("MATCH type apply")
          matchAndResolveTreeBindings(
            (fun, fun2) :: args.zip(args2), depth + 1
          )
        
        case (patternUniv.Apply(a, b), candidateUniv.Apply(a2, b2)) =>
          //println("MATCH apply")
          matchAndResolveTreeBindings(
            (a, a2) :: b.zip(b2), depth + 1
          )
          
        case (patternUniv.Block(l, v), candidateUniv.Block(l2, v2)) =>
          //println("MATCH block")
          matchAndResolveTreeBindings(
            (v, v2) :: l.zip(l2), depth + 1
          )(
            internalDefs ++ getNamesDefinedIn(patternUniv)(l)
          )
          
        case (patternUniv.Select(a, n), candidateUniv.Select(a2, n2)) 
        if n.toString == n2.toString =>
          //println("MATCH select")
          //println("Matched select " + a + " vs. " + a2)
            matchAndResolveTreeBindings(
              a, a2, depth + 1
            )
        
        // TODO
        //case (ClassDef(mods, name, tparams, impl), ClassDef(mods2, name2, tparams2, impl2)) =
        //  matchAndResolveTreeBindings(impl, impl)(internalDefs + name)
        
        case (_, candidateUniv.TypeApply(target, typeArgs)) 
        if workAroundMissingTypeApply =>
          //println("MATCH type apply + workaround")
          //println("Workaround for missing TypeApply in pattern... (loosing types " + typeArgs + ")")
          matchAndResolveTreeBindings(pattern, target, depth + 1)
        
        case (patternUniv.AppliedTypeTree(tpt, args), candidateUniv.AppliedTypeTree(tpt2, args2)) 
        if args.size == args2.size =>
          //println("MATCH applied type trees " + desc)
          matchAndResolveTreeBindings(tpt, tpt2, depth + 1) ++
          matchAndResolveTreeBindings(args.zip(args2), depth + 1)
          
        case (patternUniv.SelectFromTypeTree(qualifier, name), candidateUniv.SelectFromTypeTree(qualifier2, name2)) 
        if name.toString == name2.toString =>
          //println("MATCH select from type trees " + desc)
          matchAndResolveTreeBindings(qualifier, qualifier2, depth + 1)
          
        case (patternUniv.SingletonTypeTree(ref), candidateUniv.SingletonTypeTree(ref2)) =>
          //println("MATCH singleton type trees " + desc)
          matchAndResolveTreeBindings(ref, ref2, depth + 1)
          
        case (patternUniv.TypeTree(), candidateUniv.TypeTree()) 
        if pattern.toString == "<type ?>" =>
          //println("MATCH <type ?> tree " + desc)
          EmptyBindings
          
        case _ =>
          if (Option(pattern).toString == Option(tree).toString) {
            //println("WARNING: Monkey matching of " + pattern + " vs. " + tree)
            EmptyBindings
          } else {
            //if (depth > 0)
            //    println("TREE MISMATCH \n\texpected = " + toTypedString(pattern) + "\n\t\t" + pattern.getClass.getName + "\n\tfound = " + toTypedString(tree) + "\n\t\t" + tree.getClass.getName)
            throw NoTreeMatchException(pattern, tree, "Different trees", depth)
          }
      }
      
      if (false) {
        println("Successfully bound trees (depth " + depth + "):")
        println("\ttree pattern = " + pattern + ": " + clstr(pattern))// + "; kind = " + Option(pattern).map(_.typeSymbol.kind))
        println("\ttree found = " + tree + ": " + clstr(tree))
      }
      ret
    }
  }
  private def toTypedString(v: Any) = 
    v + Option(v).map(_ => ": " + v.getClass.getName + " <- " + v.getClass.getSuperclass.getSimpleName).getOrElse("")
                
  
  def getOrFixType(u: api.Universe)(tree: u.Tree) = {
    import u._
    val t = tree.tpe
    if (t == null)
      tree match {
        case Literal(Constant(v)) =>
          v match {
            case _: Int => IntTpe
            case _: Short => ShortTpe
            case _: Long => LongTpe
            case _: Byte => ByteTpe
            case _: Double => DoubleTpe
            case _: Float => FloatTpe
            case _: Char => CharTpe
            case _: Boolean => BooleanTpe
            case _: String => StringTpe
            case _: Unit => UnitTpe
            //case null => UnitTpe // TODO hem...
            case _ =>
              null
          }
        case _ =>
          //println("Cannot fix type for " + tree + ": " + clstr(tree))
          null
      }
    else
      t
  } 
}