package org.scalaclean.analysis

import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.reflect.internal.util.NoPosition

trait ModelSymbolBuilder extends SemanticdbOps {

  private var mSymbolCache = Map[global.Symbol, ModelSymbol]()
  def asMSymbol(gSym: global.Symbol): ModelSymbol = {

    mSymbolCache.getOrElse(gSym,{
      val isGlobal = gSym.isSemanticdbGlobal
      val sString = gSym.toSemantic
      val (startPos,endPos) = if(gSym.pos == NoPosition) (-1,-1) else (gSym.pos.start, gSym.pos.end)
      val sourceFile = if(gSym.sourceFile != null)
        mungeUnitPath(gSym.sourceFile.toString)
      else
        "-"
      val name = gSym.nameString
      val mSym = ModelSymbol(isGlobal, sString, sourceFile, startPos, endPos, gSym.isSynthetic, gSym.isAbstract, gSym.isLazy, name)

      mSymbolCache += gSym -> mSym
      mSym
    }
    )
  }

  // TODO this is a total hack - need to discover the source root of the compilation unit and remove
  def mungeUnitPath(input: String): String = {
    val idx = input.indexOf("src/main/scala")
    if (idx != -1)
      input.substring(idx + "src/main/scala".length + 1)
    else input
  }

}
