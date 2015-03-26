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

import scala.reflect.api.Universe
import scala.collection.immutable.NumericRange

trait MiscMatchers extends Tuploids {
  val global: Universe
  import global._
  import definitions._

  def verbose: Boolean

  def ownerChain(s: Symbol): List[Symbol] = {
    if (s == NoSymbol)
      Nil
    else {
      val o = s.owner
      o :: ownerChain(o)
    }
  }

  // See scala.reflect.internal.TreeInfo.methPart
  def methPart(tree: Tree): Tree = tree match {
    case Apply(f, _) => methPart(f)
    case TypeApply(f, _) => methPart(f)
    case AppliedTypeTree(f, _) => methPart(f)
    case _ => tree
  }

  /** Strips apply nodes looking for type application. */
  def typeArgs(tree: Tree): List[Tree] = tree match {
    case Apply(fn, _) => typeArgs(fn)
    case TypeApply(fn, args) => args
    case AppliedTypeTree(fn, args) => args
    case _ => Nil
  }
  /** Smashes directly nested applies down to catenate the argument lists. */
  def flattenApply(tree: Tree): List[Tree] = tree match {
    case Apply(fn, args) => flattenApply(fn) ++ args
    case _ => Nil
  }
  def flattenApplyGroups(tree: Tree): List[List[Tree]] = tree match {
    case Apply(fn, args) => flattenApplyGroups(fn) :+ args
    case _ => Nil
  }
  /** Smashes directly nested selects down to the inner tree and a list of names. */
  def flattenSelect(tree: Tree): (Tree, List[Name]) = tree match {
    case Select(qual, name) => flattenSelect(qual) match { case (t, xs) => (t, xs :+ name) }
    case _ => (tree, Nil)
  }
  /** Creates an Ident or Select from a list of names. */
  def mkSelect(names: TermName*): Tree = names.toList match {
    case Nil => EmptyTree
    case x :: Nil => Ident(x)
    case x :: xs => xs.foldLeft(Ident(x): Tree)(Select(_, _))
  }

  class Ids(start: Long = 1) {
    private var nx = start
    def next = this.synchronized {
      val v = nx
      nx += 1
      v
    }
  }

  object ScalaMathFunction {
    /**
     * I'm all for avoiding "magic strings" but in this case it's hard to
     *  see the twice-as-long identifiers as much improvement.
     */
    def apply(functionName: String, args: List[Tree]) =
      Apply(
        mkSelect(
          TermName("scala"),
          TermName("math"),
          TermName("package"),
          TermName(functionName)),
        args)

    def unapply(tree: Tree): Option[(Type, Name, List[Tree])] = tree match {
      case Apply(f @ Select(left, name), args) =>
        if (left.symbol == ScalaMathPackage ||
          left.symbol == ScalaMathPackageClass ||
          left.tpe == ScalaMathPackageClass.asType.toType)
          Some((f.tpe, name, args))
        else if (tree.symbol != NoSymbol &&
          tree.symbol.owner == ScalaMathPackage) // ScalaMathCommonClass)
          Some((f.tpe, name, args))
        else
          None
      case _ =>
        None
    }
  }

  object NumRange {
    def apply(rangeTpe: Type, numTpe: Type, from: Tree, to: Tree, by: Option[Tree], isUntil: Boolean, filters: List[Tree]) = sys.error("not implemented")

    object WrapperName {
      def apply(rangeTpe: Type, numTpe: Type) = ???
      def unapply(name: Name): Option[(Type, Type)] = Option(name) collect {
        case N("intWrapper") => (typeOf[Range], IntTpe)
        case N("longWrapper") => (typeOf[NumericRange[Long]], LongTpe)
      }
    }
    def unapply(tree: Tree): Option[(Type, Type, Tree, Tree, Option[Tree], Boolean, List[Tree])] = {
      if (tree.tpe == null || tree.tpe <:< typeOf[Range] || tree.tpe <:< typeOf[NumericRange[Long]])
        tree match {
          case Apply(Select(Apply(Select(PredefModuleTree(), WrapperName(rangeTpe, numTpe)), List(from)), N(funToName @ ("to" | "until"))), List(to)) =>
            Some((rangeTpe, numTpe, from, to, None, funToName == "until", Nil))
          case Apply(Select(tg, N(n @ ("by" | "withFilter" | "filter"))), List(arg)) =>
            tg match {
              case NumRange(rangeTpe, numTpe, from, to, by, isUntil, filters) =>
                Option(n) collect {
                  case "by" if by == None =>
                    (rangeTpe, numTpe, from, to, Some(arg), isUntil, filters)
                  case "withFilter" | "filter" /* if !options.stream */ =>
                    (rangeTpe, numTpe, from, to, by, isUntil, filters :+ arg)
                }
              case _ =>
                None
            }
          case _ =>
            None
        }
      else
        None
    }
  }

  object TreeWithSymbol {
    def unapply(tree: Tree): Option[(Tree, Symbol)] =
      Some(tree, tree.symbol)
  }
  object TreeWithType {
    def unapply(tree: Tree): Option[(Tree, Type)] =
      Some((tree, if (tree.tpe == null) null else normalize(tree.tpe)))
  }
  object SymbolWithOwnerAndName {
    def unapply(sym: Symbol): Option[(Symbol, Symbol, Name)] =
      Some(sym, sym.owner, sym.name)
  }
  object TupleClass {
    def unapply(sym: Symbol): Boolean =
      isTupleSymbol(sym)
  }

  object tupleComponentName {
    val rx = "_(\\d+)".r
    def unapply(n: Name): Option[Int] = {
      n.toString match {
        case rx(n) =>
          Some(n.toInt)
        case _ =>
          None
      }
    }
  }

  object TupleComponent {
    val rx = "_(\\d+)".r
    def unapply(tree: Tree) = tree match {
      /*case 
        TreeWithSymbol(
          Select(
            target, 
            tupleComponentName(_)
          ),
          SymbolWithOwnerAndName(
            _, 
            TupleClass(),
            tupleComponentName(n)
          )
        ) =>
        Some(target, n - 1)
      */
      case Select(target, tupleComponentName(n)) =>
        //fieldName.toString match {
        //  case rx(n) =>
        if (tree.symbol != NoSymbol && isTupleSymbol(tree.symbol.owner) || isTupleSymbol(target.tpe.typeSymbol)) {
          Some(target, n - 1)
        } else {
          println("ISSUE with tuple target symbol \n\t" + target.symbol + "\n\t" + target.tpe.typeSymbol)
          None
        }
      //case _ =>
      //  None
      //}
      case _ =>
        None
    }
  }
  object TuplePath {
    def unapply(tree: Tree) = {
      var lastTarget: Tree = tree
      var path: List[Int] = Nil
      var finished = false
      while (!finished) {
        lastTarget match {
          case TupleComponent(target, i) =>
            path = i :: path
            lastTarget = target
          case _ =>
            finished = true
        }
      }
      if (path.isEmpty)
        None
      else
        Some((lastTarget, path))
    }
  }
  object WhileLoop {
    def unapply(tree: Tree) = tree match {
      case LabelDef(
        lab,
        List(),
        If(
          condition,
          Block(
            content,
            Apply(
              Ident(lab2),
              List()
              )
            ),
          Literal(Constant(()))
          )
        ) if (lab == lab2) =>
        Some(condition, content)
      case _ =>
        None
    }
  }
  lazy val unitTpe = UnitTpe
  def isUnit(tpe: Type) = normalize(tpe) match {
    case `unitTpe` | MethodType(_, `unitTpe`) =>
      true
    case _ =>
      false
  }

  def isTupleSymbol(sym: Symbol) =
    sym.toString.matches("class Tuple\\d+")

  def isAnyVal(tpe: Type) =
    tpe == IntTpe ||
      tpe == ShortTpe ||
      tpe == LongTpe ||
      tpe == ByteTpe ||
      tpe == DoubleTpe ||
      tpe == FloatTpe ||
      tpe == CharTpe ||
      tpe == BooleanTpe

  def getArrayType(componentType: Type, dimensions: Int = 1): Type = {
    assert(dimensions >= 1)
    appliedType(
      // ArrayClass.asType.toType,
      typeOf[Array[_]],
      List(
        if (dimensions == 1)
          componentType
        else
          getArrayType(componentType, dimensions - 1)
      )
    )
  }

  def getOptionType(componentType: Type) = appliedType(typeOf[Option[_]], List(componentType))
  def getListType(componentType: Type) = appliedType(typeOf[List[_]], List(componentType))

  object BasicTypeApply {
    def unapply(tree: Tree): Option[(Tree, Name, List[Tree], Seq[List[Tree]])] = tree match {
      case Apply(
        TypeApply(
          Select(collection, name),
          typeArgs
          ),
        args
        ) =>
        Some((collection, name, typeArgs, Seq(args)))
      case Apply(BasicTypeApply(collection, name, typeArgs, args), newArgs) =>
        Some((collection, name, typeArgs, args :+ newArgs))
      case _ =>
        None
    }
  }

  object Foreach {
    def unapply(tree: Tree): Option[(Tree, Function)] = Option(tree) collect {
      case Apply(TypeApply(Select(collection, N("foreach")), _), List(function @ Function(_, _))) =>
        (collection, function)
      case Apply(Select(collection, N("foreach")), List(function @ Function(_, _))) =>
        // Non-typed foreach lacks the TypeApply.
        (collection, function)
    }
  }

  lazy val scalaPackage = ScalaPackage
  object TupleSelect {
    private def isTupleName(name: Name) =
      name.toString.matches(""".*Tuple\d+""")

    def unapply(tree: Tree) = tree match {
      case Select(Ident(scalaPackage), name) if isTupleName(name) =>
        true
      case Ident(name) if isTupleName(name) =>
        true
      case _ =>
        false
      //name.toString.matches("""(_root_\.)?scala\.Tuple\d+""")
    }
  }

  object TupleCreation {
    def unapply(tree: Tree): Option[List[Tree]] = Option(tree) collect {
      case Apply(TypeApply(Select(TupleSelect(), N("apply")), types), components) if isTupleType(tree.tpe) =>
        components
      case Apply(tt @ TypeTree(), components) if isTupleSymbol(tree.tpe.typeSymbol) =>
        // TODO FIX THIS BROAD HACK !!! (test tt)
        //println("tt.tpe = (" + tt.tpe + ": " + tt.tpe.getClass.getName + ")")
        components
    }
  }

  // def resolveType(t: Type) = t match {
  //   case TypeRef(_, sym, args) if sym.asType.isAliasType =>
  //     t.dealias
  //   case _ =>
  //     t
  // }
  class CollectionApply(colModule: Symbol, colTpe: Type) {
    val colType = normalize(colTpe)
    //val colTpe = colClass.typeSignature
    def matchesColModule(s: Symbol) = {
      s == colModule ||
        s.typeSignature.typeSymbol == colModule ||
        s.typeSignature.typeSymbol.toString == colModule.toString
    }
    def apply(component: Tree) = sys.error("not implemented")
    def unapply(tree: Tree): Option[(List[Tree], Type)] = {
      val tpe = normalize(tree.tpe)
      if (tpe != null && tpe <:< colType) {
        tree match {
          case Apply(TypeApply(Select(colObject, N("apply")), List(_)), components) if matchesColModule(colObject.symbol) =>
            val TypeRef(_, colClass, List(componentType)) = normalize(tree.tpe)
            Some(components, componentType)
          // case Apply(TypeApply(Select(colObject, N("apply")), List(_)), components) =>
          //   val TypeRef(_, colClass, List(componentType)) = normalize(tree.tpe)
          //   println(s"""NO DEAL:
          //     colObject.symbol = ${colObject.symbol}
          //     colObject.symbol.typeSignature.typeSymbol = ${colObject.symbol.typeSignature.typeSymbol}
          //     colModule = $colModule
          //     colModule.typeSignature.typeSymbol = ${colModule.typeSignature.typeSymbol}
          //   """)
          //   println
          //   None
          case Apply(Select(colObject, N("apply")), components) if matchesColModule(colObject.symbol) =>
            val TypeRef(_, colClass, List(componentType)) = normalize(tree.tpe)
            Some(components, componentType)
          case _ =>
            None
        }
      } else {
        // println("NO DEAL: colType = " + colType + ", tree.tpe = " + tree.tpe)
        None
      }
    }
  }

  object OptionApply extends CollectionApply(OptionModule, typeOf[Option[_]])
  object ArrayApply extends CollectionApply(ArrayModule, typeOf[Array[_]])
  object SeqApply extends CollectionApply(SeqModule, typeOf[Seq[_]])
  object IndexedSeqApply extends CollectionApply(IndexedSeqModule, typeOf[IndexedSeq[_]])
  object ListApply extends CollectionApply(ListModule, typeOf[List[_]])
  object VectorApply extends CollectionApply(VectorModule, typeOf[Vector[_]])

  def normalize(tpe: Type): Type =
    Option(tpe).map(_.dealias.widen).orNull

  object OptionTree {
    val optionClass = OptionClass
    def apply(componentType: Type) = sys.error("not implemented")
    //TODO <:< typeOf[Option[_]]
    def unapply(tree: Tree): Option[Type] = tree match {
      case TypeTree() =>
        None
      case _ if tree.tpe != null && tree.tpe <:< typeOf[Option[_]] =>
        val TypeRef(_, optionClass, List(componentType)) = normalize(tree.tpe)
        Some(componentType)
      case _ =>
        None
    }
  }
  object PredefModuleTree {
    lazy val RefArrayOps = this("refArrayOps")
    lazy val GenericArrayOps = this("genericArrayOps")
    lazy val IntWrapper = this("intWrapper")
    lazy val println = this("println")

    def contains(sym: Symbol) = sym.owner == PredefModule.moduleClass
    def apply(name: String): Symbol = PredefModule.asModule.moduleClass.asType.toType member TermName(name)
    def unapply(tree: Tree): Boolean = tree.symbol == PredefModule
  }
  object ArrayOps {
    val arrayOpsClass = ArrayOpsClass
    def unapply(tree: Tree): Option[Type] = tree match {
      case TypeApply(sel, List(arg)) if sel.symbol == PredefModuleTree.RefArrayOps || sel.symbol == PredefModuleTree.GenericArrayOps =>
        Some(arg.tpe)
      case _ => tree.tpe match {
        case MethodType(_, TypeRef(_, arrayOpsClass, List(param))) if PredefModuleTree contains tree.symbol =>
          Some(param)
        case _ =>
          None
      }
    }
  }

  object WrappedArrayTree {
    def unapply(tree: Tree) = tree match {
      case Apply(ArrayOps(componentType), List(array)) => Some(array, componentType)
      case _ => None
    }
  }
  object ArrayTyped extends HigherTypeParameterExtractor(ArrayClass)

  class HigherTypeParameterExtractor(ColClass: Symbol) {
    private def isCol(s: Symbol) =
      s.asType.toType == ColClass.asType.toType || s.asType.toType.toString == ColClass.asType.toType.toString
    private def isCol2(s: Symbol) =
      isCol(s) || isCol(s.asType.toType.typeSymbol)

    def unapply(tpe: Type): Option[Type] = Option(normalize(tpe)) collect {
      case TypeRef(_, ColClass, List(param)) =>
        param
      case TypeRef(_, cc, List(param)) if isCol2(cc) => //tree.symbol)  => 
        param
      case PolyType(Nil, TypeRef(_, cc, List(param))) if isCol2(cc) =>
        param
    }

    def unapply(tree: Tree): Option[Type] = if (tree == null) None else {
      unapply(tree.tpe) match {
        case Some(s) =>
          Some(s)
        case None =>
          if ((tree ne null) && (tree.symbol ne null) && tree.symbol != NoSymbol)
            unapply(tree.symbol.typeSignature)
          else
            None
      }
    }
  }
  object ListTree extends HigherTypeParameterExtractor(ListClass)

  object ArrayTabulate {
    /** This is the one all the other ones go through. */
    lazy val tabulateSyms = ArrayModule.asModule.moduleClass.asType.toType.members.filter(_.name.toString == "tabulate").toSet //filter (_.paramLists.flatten.size == 3)

    def apply(componentType: Tree, lengths: List[Tree], function: Tree, manifest: Tree) = sys.error("not implemented")
    def unapply(tree: Tree): Option[(Tree, List[Tree], Tree, Tree)] = {
      if (!tabulateSyms.contains(methPart(tree).symbol))
        None
      else flattenApplyGroups(tree) match {
        case List(lengths, List(function), List(manifest)) =>
          Some((typeArgs(tree).headOption getOrElse EmptyTree, lengths, function, manifest))
        case _ =>
          None
      }
    }
  }

  object CanBuildFromArg {
    private val canBuildFromTpe = typeOf[scala.collection.generic.CanBuildFrom[_, _, _]]
    def unapply(tree: Tree) = {
      val tpe = tree.tpe
      tpe != null && tpe <:< canBuildFromTpe
    }
  }

  object Func {
    def unapply(tree: Tree): Option[(List[ValDef], Tree)] = Option(tree) collect {
      case Block(List(), Func(params, body)) =>
        // method references def f(x: T) = y; col.map(f) (inside the .map = a block((), xx => f(xx))
        (params, body)
      case Function(params, body) =>
        (params, body)
    }
  }
}
