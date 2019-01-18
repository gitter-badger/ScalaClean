package scalaclean.deadcode

import scalaclean.model._
import scalaclean.util.{Scope, SymbolTreeVisitor, TokenHelper}
import scalafix.v1._

import scala.meta.{Defn, Pat, Stat}

/**
  * A rule that removes unreferenced classes,
  * needs to be run after Analysis
  */
class ScalaCleanDeadCodeRemover extends SemanticRule("ScalaCleanDeadCodeRemover") {

  sealed trait Purpose {
    def id: Int
  }

  object Main extends Purpose {
    override def id: Int = 1
  }

  object Test extends Purpose {
    override def id: Int = 1 << 1
  }

  object Usage {
    private val usages = Array.tabulate(3) {
      Usage(_)
    }
    val unused = usages(0)
  }

  case class Usage(purposes: Int) extends Colour {
    def withPurpose(purpose: Purpose): Usage = {
      Usage.usages(purposes | purpose.id)
    }

    def hasPurpose(purpose: Purpose): Boolean =
      0 != (purposes & purpose.id)

    def isUnused = {
      purposes == 0
    }
  }

  var model: ScalaCleanModel = _


  def markInitial = {
    model.allOf[ModelElement].foreach {
      e => e.colour = Usage.unused
    }
  }

  def allMainEntryPoints = {
    allMainMethodEntries ++ allApp
  }

  def allMainMethodEntries = {
    val stringArray = List(List(Symbol))
    (for (obj <- model.allOf[ObjectModel] if (obj.enclosing.isEmpty);
          method <- obj.methods if method.name == "main") //&& method.paramsType = stringArray
      yield {
        List(method, obj)
      }).flatten
  }

  def allApp = {
    for (obj <- model.allOf[ObjectModel] if (obj.xtends[App]))
      yield obj
  }

  //  def allTestEntryPoints = {
  //    allMainMethodEntries ++ allApp
  //  }
  //  def allJunitTest = {
  //    model.allOf[MethodModel] collect {
  //      case method if (method.annotations)
  //    }
  //  }

  def markUsed(element: ModelElement, purpose: Purpose): Unit = {
    def markRhs(element: ModelElement): Unit = {
      element.fields foreach {
        case valDef: ValModel => if (!valDef.isLazy) {
          valDef.internalOutgoingReferences foreach {
            case (ref, _) => markUsed(ref, purpose)
          }
          markRhs(valDef)
        }
        case varDef: VarModel =>
          varDef.internalOutgoingReferences foreach {
            case (ref, _) => markUsed(ref, purpose)
          }
          markRhs(varDef)
        case obj: ObjectModel =>
          obj.internalOutgoingReferences foreach {
            case (ref, _) => markUsed(ref, purpose)
          }
          markRhs(obj)
      }
    }
    val current = element.colour.asInstanceOf[Usage]

    if (!current.hasPurpose(purpose)) {
      element.colour = current.withPurpose(purpose)
      element.internalOutgoingReferences foreach {
        case (ref, _) => markUsed(ref, purpose)
      }
      markRhs(element)
      //TODO for the VARs and (non lazy) vals and objects eagerly traverse

      //TODO consider marking the overridded and overrides
      //TODO consider marking the inherited classes
      //TODO consider marking the enclosing like markUsed(element.owner)
    }
  }

  override def beforeStart(): Unit = {
    println(s"$name beforeStart")
    // load the model from the helper class
    this.model = ModelHelper.model.getOrElse(throw new IllegalStateException("No model to work from"))

    markInitial

    allMainEntryPoints foreach ( e=> markUsed(e, Main))
  }

  override def fix(implicit doc: SemanticDocument): Patch = {

    val tv = new SymbolTreeVisitor {

      override protected def handlerSymbol(symbol: Symbol, stat: Stat, scope: List[Scope]): (Patch, Boolean) = {
        val modelElement = model.fromSymbol[ModelElement](symbol)
        val usage = modelElement.colour.asInstanceOf[Usage]
        if (usage.isUnused) {
          val tokens = stat.tokens
          val firstToken = tokens.head

          (Patch.removeTokens(TokenHelper.whitespaceTokensBefore(firstToken, doc.tokens)) + Patch.removeTokens(tokens), false)
        } else
          (Patch.empty, true)
      }

      override protected def handlerPats(pats: Seq[Pat.Var], stat: Stat, scope: List[Scope]): (Patch, Boolean) = {
        val declarationsByUsage: Map[Usage, Seq[(Pat.Var,ModelElement)]] =
          pats map (p => (p,model.fromSymbol[ModelElement](p.symbol))) groupBy(m => m._2.colour.asInstanceOf[Usage])
        declarationsByUsage.get(Usage.unused) match {
          case Some(_) if declarationsByUsage.size == 1 =>
            //we can remove the whole declaration
            val tokens = stat.tokens
            val firstToken = tokens.head
            (Patch.removeTokens(TokenHelper.whitespaceTokensBefore(firstToken, doc.tokens)) + Patch.removeTokens(tokens), false)
          case Some(unused)  =>
            val combinedPatch = unused.foldLeft(Patch.empty){
              case (patch, (pat, model)) =>
                patch + Patch.replaceToken(pat.tokens.head, "_")
            }
            (combinedPatch, true)
          case _ =>
            (Patch.empty, true)

        }

      }
    }
    tv.visitDocument(doc.tree)
  }
}
