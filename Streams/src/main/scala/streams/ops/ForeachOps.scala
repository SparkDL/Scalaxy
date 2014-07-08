package scalaxy.streams

private[streams] trait ForeachOps
    extends ClosureStreamOps
    with UnusableSinks
{
  val global: scala.reflect.api.Universe
  import global._

  object SomeForeachOp {
    def unapply(tree: Tree): Option[(Tree, ForeachOp)] = Option(tree) collect {
      case q"$target.foreach[${_}](${Strip(Function(List(param), body))})" =>
        (target, ForeachOp(param, body))
    }
  }
  case class ForeachOp(param: ValDef, body: Tree)
      extends ClosureStreamOp
  {
    override def describe = Some("foreach")

    override def sinkOption = Some(UnitSink)

    /// Technically, the output size of the Unit output is zero, so it's altered.
    override def canAlterSize = true

    override def emit(input: StreamInput,
                      outputNeeds: OutputNeeds,
                      nextOps: OpsAndOutputNeeds): StreamOutput =
    {
      val List((UnitSink, _)) = nextOps

      val (replacedStatements, outputVars) =
        transformationClosure.replaceClosureBody(input, outputNeeds)

      // require(outputVars.tpe.dealias =:= typeOf[Unit], "Expected Unit, got " + outputVars.tpe)

      StreamOutput(body = replacedStatements)
    }
  }
}
