/*
 * Created by IntelliJ IDEA.
 * User: ochafik
 * Date: 10/05/11
 * Time: 21:40
 */
package scalaxy.components

import scala.language.implicitConversions
import scala.language.postfixOps

import scala.reflect.api.Universe

trait TraversalOps
    extends CommonScalaNames
    with Streams
    with StreamSinks {
  val global: Universe
  import global._
  import definitions._

  sealed abstract class TraversalOpType {
    val needsInitialValue = false
    val needsFunction = false
    val loopSkipsFirst = false
    val f: Tree
  }

  case class TraversalOp(
    op: TraversalOpType,
    collection: Tree,
    resultType: Type,
    mappedCollectionType: Type,
    isLeft: Boolean,
    initialValue: Tree)

  trait ScalarReduction extends Reductoid {
    override def resultKind = ScalarResult
    override def transformedValue(value: StreamValue, totalVar: ValueDef, initVarOpt: Option[ValueDef])(implicit loop: Loop): StreamValue =
      null
  }
  trait SideEffectFreeScalarReduction extends ScalarReduction with SideEffectFreeStreamComponent

  trait FunctionTransformer extends StreamTransformer {
    def f: Tree
    override def closuresCount = 1
    override def analyzeSideEffectsOnStream(analyzer: SideEffectsAnalyzer) =
      analyzer.analyzeSideEffects(tree, f)
    // Initial value does not affect the stream :
    // && sideEffectsAnalyzer.isSideEffectFree(initialValue)
  }
  trait Function1Transformer extends FunctionTransformer {
    lazy val Func(List(arg), body) = f
    def transformedFunc(value: StreamValue)(implicit loop: Loop) =
      replaceOccurrences(
        loop.transform(body),
        Map(
          arg.symbol -> (() => value.value())
        ),
        Map(f.symbol -> loop.currentOwner),
        Map()
      )
  }
  trait Function2Reduction extends Reductoid with FunctionTransformer {
    def isLeft: Boolean
    lazy val Func(List(leftParam, rightParam), body) = f

    override def order = if (isLeft) SameOrder else ReverseOrder
    override def updateTotalWithValue(total: TreeGen, value: TreeGen)(implicit loop: Loop): ReductionTotalUpdate = {
      import loop.{ currentOwner }
      val result = replaceOccurrences(
        loop.transform(body),
        Map(
          leftParam.symbol -> total,
          rightParam.symbol -> value
        ),
        Map(f.symbol -> currentOwner),
        Map()
      )
      //val resultVar = newVar("res$",
      //  result
      //)
      //loop.inner += resultVar.definition
      ReductionTotalUpdate(result) //Var())
    }
  }
  trait Reductoid extends StreamTransformer {
    //def loopSkipsFirst: Boolean
    //def isLeft: Boolean
    //def initialValue: Option[Tree]
    def op: String = toString

    case class ReductionTotalUpdate(newTotalValue: Tree, conditionOpt: Option[Tree] = None)

    def getInitialValue(value: StreamValue): Tree

    def providesInitialValue(value: StreamValue): Boolean =
      getInitialValue(value) != null

    def hasInitialValue(value: StreamValue) =
      providesInitialValue(value) || value.extraFirstValue != None

    def needsInitialValue: Boolean

    def updateTotalWithValue(total: TreeGen, value: TreeGen)(implicit loop: Loop): ReductionTotalUpdate

    def createInitialValue(value: StreamValue)(implicit loop: Loop): Tree = {
      //println("value.extraFirstValue = " + value.extraFirstValue)
      val iv = getInitialValue(value)
      (Option(iv), value.extraFirstValue) match {
        case (Some(i), Some(e)) =>
          updateTotalWithValue(() => i, () => e()).newTotalValue
        case (None, Some(e)) =>
          e()
        case (Some(i), None) =>
          i
        case (None, None) =>
          newDefaultValue(value.tpe)
      }
    }

    def transformedValue(value: StreamValue, totalVar: ValueDef, initVarOpt: Option[ValueDef])(implicit loop: Loop): StreamValue

    def throwsIfEmpty(value: StreamValue) = needsInitialValue && !hasInitialValue(value)

    def someIf[V](cond: Boolean)(v: => V): Option[V] =
      if (cond) Some(v) else None

    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      import loop.{ currentOwner }

      val hasInitVal = hasInitialValue(value)

      val initVal =
        if (hasInitVal)
          createInitialValue(value)
        else
          newDefaultValue(value.tpe)

      val initVarOpt = someIf(hasInitVal || producesExtraFirstValue) {
        newVal(op + "$init", initVal, value.tpe)
      }
      val totVar = newVar(op + "$",
        initVarOpt.map(_()).getOrElse(initVal), value.tpe
      )

      val mayNotBeDefined = throwsIfEmpty(value) && needsInitialValue && !hasInitVal

      //println("op " + op + " : mayNotBeDefined = " + mayNotBeDefined + ", providesInitialValue = " + providesInitialValue + ", hasInitVal = " + hasInitVal)

      val isDefinedVarOpt = someIf(mayNotBeDefined) {
        newVar("is$" + op + "$defined",
          newBool(false), BooleanTpe
        )
      }

      for (initVar <- initVarOpt)
        loop.preOuter += initVar.definition
      loop.preOuter += totVar.definition

      val ReductionTotalUpdate(newTotalValue, conditionOpt) =
        updateTotalWithValue(totVar.identGen, value.value)

      val totAssign = newAssign(totVar, newTotalValue)

      val update =
        conditionOpt.map(newIf(_, totAssign)).getOrElse(totAssign)

      isDefinedVarOpt match {
        case Some(isDefinedVar) =>
          loop.preOuter += isDefinedVar.definition

          loop.inner += newIf(
            boolNot(isDefinedVar()),
            Block(
              Assign(isDefinedVar(), newBool(true)) ::
                newAssign(totVar, value.value()) :: Nil,
              newUnit
            ),
            update
          )

          loop.postOuter +=
            newIf(
              boolNot(isDefinedVar()),
              Throw(
                Apply(
                  Select(
                    New(TypeTree(ArrayIndexOutOfBoundsExceptionClass.asType.toType)),
                    nme.CONSTRUCTOR
                  ),
                  List(newInt(0))
                )
              )
            )
        case None =>
          loop.inner += update
      }

      loop.postOuter += totVar()

      transformedValue(
        if (producesExtraFirstValue)
          value.copy(extraFirstValue = initVarOpt.map(initVar => new DefaultTupleValue(initVar)))
        else
          value,
        totVar,
        initVarOpt
      )
    }
  }

  case class FoldOp(tree: Tree, f: Tree, initialValue: Tree, isLeft: Boolean) extends TraversalOpType with ScalarReduction with Function2Reduction {
    override def toString = "fold" + (if (isLeft) "Left" else "Right")
    override val needsFunction: Boolean = true
    override val needsInitialValue = true
    override def getInitialValue(value: StreamValue) = initialValue
    override def throwsIfEmpty(value: StreamValue) = false

    override def consumesExtraFirstValue = true
  }
  case class ScanOp(tree: Tree, f: Tree, initialValue: Tree, isLeft: Boolean, canBuildFrom: Tree) extends TraversalOpType with Function2Reduction {
    override def toString = "scan" + (if (isLeft) "Left" else "Right")
    override val needsFunction: Boolean = true
    override val needsInitialValue = true
    override def getInitialValue(value: StreamValue) = initialValue
    override def throwsIfEmpty(value: StreamValue) = false

    override def consumesExtraFirstValue = true
    override def producesExtraFirstValue = true

    override def transformedValue(value: StreamValue, totalVar: ValueDef, initVarOpt: Option[ValueDef])(implicit loop: Loop): StreamValue = {
      value.copy(
        value = new DefaultTupleValue(totalVar),
        extraFirstValue = initVarOpt.map(initVar => new DefaultTupleValue(initVar))
      )
    }

    override def order = SameOrder
  }
  case class ReduceOp(tree: Tree, f: Tree, isLeft: Boolean) extends TraversalOpType with ScalarReduction with Function2Reduction {
    override def toString = "reduce" + (if (isLeft) "Left" else "Right")
    override val needsFunction: Boolean = true
    override val loopSkipsFirst = true
    override def getInitialValue(value: StreamValue) = null
    override val needsInitialValue = true
    override def throwsIfEmpty(value: StreamValue) = true

    override def consumesExtraFirstValue = true
  }
  case class SumOp(tree: Tree) extends TraversalOpType with SideEffectFreeScalarReduction {
    override def toString = "sum"
    override val f = null
    override def order = Unordered
    override def getInitialValue(value: StreamValue) = newDefaultValue(value.tpe)
    override val needsInitialValue = false
    override def throwsIfEmpty(value: StreamValue) = true

    override def updateTotalWithValue(totIdentGen: TreeGen, valueIdentGen: TreeGen)(implicit loop: Loop): ReductionTotalUpdate = {
      val totIdent = totIdentGen()
      val valueIdent = valueIdentGen()
      ReductionTotalUpdate(binOp(totIdent, PLUS, valueIdent))
    }
  }
  case class ProductOp(tree: Tree) extends TraversalOpType with SideEffectFreeScalarReduction {
    override def toString = "product"
    override val f = null
    override def order = Unordered
    override def getInitialValue(value: StreamValue) = newOneValue(value.tpe)
    override val needsInitialValue = false
    override def throwsIfEmpty(value: StreamValue) = true

    override def updateTotalWithValue(totIdentGen: TreeGen, valueIdentGen: TreeGen)(implicit loop: Loop): ReductionTotalUpdate = {
      ReductionTotalUpdate(binOp(totIdentGen(), MUL, valueIdentGen()))
    }
  }
  case class CountOp(tree: Tree, f: Tree) extends TraversalOpType with Function1Transformer {
    override def toString = "count"
    override val needsFunction: Boolean = true

    override def order = Unordered
    override def resultKind = ScalarResult
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      import loop.{ currentOwner }
      val countVar = newVar("count$", newInt(0), IntTpe)
      loop.preOuter += countVar.definition
      loop.inner +=
        newIf(
          transformedFunc(value),
          incrementIntVar(countVar.identGen)
        )
      loop.postOuter += countVar()
      null
    }
  }
  case class MinOp(tree: Tree) extends TraversalOpType with SideEffectFreeScalarReduction {
    override def toString = "min"
    override val loopSkipsFirst = true
    override val f = null
    override def order = Unordered
    override val needsInitialValue = true
    override def getInitialValue(value: StreamValue) = null

    override def updateTotalWithValue(totIdentGen: TreeGen, valueIdentGen: TreeGen)(implicit loop: Loop): ReductionTotalUpdate = {
      val totIdent = totIdentGen()
      val valueIdent = valueIdentGen()
      ReductionTotalUpdate(valueIdent, conditionOpt = Some(binOp(valueIdent, LT, totIdent)))
    }
  }
  case class MaxOp(tree: Tree) extends TraversalOpType with SideEffectFreeScalarReduction {
    override def toString = "max"
    override val loopSkipsFirst = true
    override val f = null
    override def order = Unordered
    override val needsInitialValue = true
    override def getInitialValue(value: StreamValue) = null

    override def updateTotalWithValue(totIdentGen: TreeGen, valueIdentGen: TreeGen)(implicit loop: Loop): ReductionTotalUpdate = {
      val totIdent = totIdentGen()
      val valueIdent = valueIdentGen()
      ReductionTotalUpdate(
        valueIdent,
        conditionOpt = //Some(binOp(valueIdent, GT, totIdent)))
          Some(Apply(Select(valueIdent, GT), List(totIdent))))
    }
  }
  case class FilterOp(tree: Tree, f: Tree, not: Boolean) extends TraversalOpType with Function1Transformer {
    override def toString = if (not) "filterNot" else "filter"
    override def order = Unordered
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      val cond = transformedFunc(value)

      loop.innerIf(() => {
        if (not)
          boolNot(cond)
        else
          cond
      })

      value.withoutSizeInfo
    }
  }
  case class FilterWhileOp(tree: Tree, f: Tree, take: Boolean) extends TraversalOpType with Function1Transformer {
    override def toString = if (take) "takeWhile" else "dropWhile"

    override def order = SameOrder
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      import loop.{ currentOwner }

      val passedVar = newVar("passed$", newBool(false), BooleanTpe)
      loop.preOuter += passedVar.definition

      if (take)
        loop.tests += boolNot(passedVar())

      val cond = boolNot(transformedFunc(value))

      if (take) {
        loop.inner += newAssign(passedVar, cond)
        loop.innerIf(() => boolNot(passedVar()))
      } else {
        loop.innerIf(() =>
          boolOr(
            passedVar(),
            typeCheck(
              Block(
                List(
                  typeCheck(
                    Assign(
                      passedVar(),
                      cond
                    ),
                    UnitTpe
                  )
                ),
                passedVar()
              ),
              BooleanTpe
            )
          )
        )
      }

      value.withoutSizeInfo
    }
  }
  case class MapOp(tree: Tree, f: Tree, canBuildFrom: Tree) extends TraversalOpType with Function1Transformer {
    override def toString = "map"
    override def order = Unordered
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      val TypeRef(_, _, List(_, toTpe, _)) = canBuildFrom.tpe
      val mappedVal = newVal("mapped$", transformedFunc(value), toTpe)
      loop.inner += mappedVal.definition

      value.copy(value = new DefaultTupleValue(mappedVal))
    }
  }

  case class CollectOp(tree: Tree, f: Tree, canBuildFrom: Tree) extends TraversalOpType {
    override def toString = "collect"
  }
  // case class UpdateAllOp(tree: Tree, f: Tree) extends TraversalOpType {
  //   override def toString = "update"
  // }
  case class ForeachOp(tree: Tree, f: Tree) extends TraversalOpType with Function1Transformer {
    override def toString = "foreach"
    override def order = SameOrder
    override def resultKind = NoResult
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      loop.inner += transformedFunc(value)
      null
    }
  }
  case class AllOrSomeOp(tree: Tree, f: Tree, all: Boolean) extends TraversalOpType with Function1Transformer {
    override def toString = if (all) "forall" else "exists"

    override def order = Unordered
    override def resultKind = ScalarResult
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue = {
      import loop.{ currentOwner }

      val hasTrueVar = newVar("hasTrue$", newBool(all), BooleanTpe)
      val countVar = newVar("count$", newInt(0), IntTpe)
      loop.preOuter += hasTrueVar.definition
      loop.tests += (
        if (all)
          hasTrueVar()
        else
          boolNot(hasTrueVar())
      )
      loop.inner += newAssign(hasTrueVar, transformedFunc(value))
      loop.postOuter += hasTrueVar()
      null
    }
  }
  case class FindOp(tree: Tree, f: Tree) extends TraversalOpType {
    override def toString = "find"
  }
  case class ReverseOp(tree: Tree) extends TraversalOpType with StreamTransformer with SideEffectFreeStreamComponent {
    override def toString = "reverse"
    override val f = null

    override def order = ReverseOrder

    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue =
      value.copy(valueIndex = (value.valueIndex, value.valuesCount) match {
        case (Some(i), Some(n)) =>
          Some(() => intSub(intSub(n(), i()), newInt(1)))
        case _ =>
          None
      })
  }
  case class ZipOp(tree: Tree, zippedCollection: Tree) extends TraversalOpType {
    override def toString = "zip"
    override val f = null
  }

  abstract class ToCollectionOp(val colType: ColType) extends TraversalOpType with StreamTransformer with SideEffectFreeStreamComponent {
    override def toString = "to" + colType
    override val f = null
    override def transform(value: StreamValue)(implicit loop: Loop): StreamValue =
      value

    override def order = SameOrder
  }
  // TODO
  case class ToSeqOp(tree: Tree) extends ToCollectionOp(SeqType) with CanCreateVectorSink

  case class ToListOp(tree: Tree) extends ToCollectionOp(ListType) with CanCreateListSink
  case class ToSetOp(tree: Tree) extends ToCollectionOp(SetType) with CanCreateSetSink
  case class ToArrayOp(tree: Tree) extends ToCollectionOp(ArrayType) with CanCreateArraySink {
    override def isResultWrapped = false
  }
  //case class ToOptionOp(tree: Tree, tpe: Type) extends ToCollectionOp(ListType) with CanCreateOptionSink // TODO !!!
  case class ToVectorOp(tree: Tree) extends ToCollectionOp(VectorType) with CanCreateVectorSink
  case class ToIndexedSeqOp(tree: Tree) extends ToCollectionOp(IndexedSeqType) with CanCreateVectorSink

  case class ZipWithIndexOp(tree: Tree) extends TraversalOpType {
    override def toString = "zipWithIndex"
    override val f = null
  }
}
