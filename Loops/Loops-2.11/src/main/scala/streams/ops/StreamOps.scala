package scalaxy.loops

private[loops] trait StreamOps
    extends Utils
    with StreamSources
    with InlineRangeStreamSources
    with ArrayStreamSources
    with ForeachOps
    with MapOps
    with FlatMapOps // TODO
    with FilterOps
    with ZipWithIndexOps
{
  val global: scala.reflect.api.Universe
  import global._

  object SomeStreamOp extends Extractor[Tree, (StreamSource, List[StreamOp])] {
    def unapply(tree: Tree): Option[(StreamSource, List[StreamOp])] = Option(tree) collect {
      case SomeForeachOp(SomeStreamOp(src, ops), op) =>
        (src, ops :+ op)

      case SomeMapOp(SomeStreamOp(src, ops), op) =>
        (src, ops :+ op)

      case SomeFlatMapOp(SomeStreamOp(src, ops), op @ FlatMapOp(param, body, canBuildFrom)) =>
        println("TODO: flatten flatMap !!!")
        (src, ops :+ op)

      case SomeFilterOp(SomeStreamOp(src, ops), op) =>
        (src, ops :+ op)

      case SomeZipWithIndexOp(SomeStreamOp(src, ops), op) =>
        (src, ops :+ op)

      case SomeArrayOps(SomeStreamOp(src, ops)) =>
        (src, ops)

      case SomeStreamSource(src) =>
        (src, Nil)
    }
  }
}