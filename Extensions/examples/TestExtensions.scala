object TestExtensions 
{  
  @extend(Int) def str1: String = self.toString
  
  @extend(Int) def str2: String = macro {
    println("EXECUTING EXTENSION MACRO!")
    reify(self.splice.toString)
  }
  
  /*
  @extend(Int) def str = macro reify(self.splice.toString)
  @extend(Int) def str = macro {
    ...
    reify(self.splice.toString)
  }
  */
  //println(10.str)
}
