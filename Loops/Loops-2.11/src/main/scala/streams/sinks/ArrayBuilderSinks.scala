package scalaxy.loops

import scala.collection.generic.CanBuildFrom

private[loops] trait ArrayBuilderSinks extends BuilderSinks {
  val global: scala.reflect.api.Universe
  import global._

  case object ArrayBuilderSink extends BuilderSink
  {
    override def describe = Some("Array")

    // TODO build array of same size as source collection if it is known.
    override def createBuilder(inputVars: TuploidValue[Tree]) = {
      val builderModule = rootMirror.staticModule("scala.collection.mutable.ArrayBuilder")
      typed(q"$builderModule.make[${inputVars.tpe}]")
    }
  }
}