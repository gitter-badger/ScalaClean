package scalaclean.rules.privatiser

import scalaclean.model.{Mark, ModelElement, Utils}
import scalaclean.util.SymbolUtils
import scalafix.patch.Patch
import scalafix.v1.Symbol

import scala.meta.Stat

private[privatiser] sealed trait PrivatiserLevel extends Mark {
  def shouldReplace(aModel: ModelElement): Boolean

  def reason: String

  def asText(context: ModelElement): Option[String]

  def widen(level: PrivatiserLevel): PrivatiserLevel

  def marker(stat: Stat): Patch = Patch.empty
}

private[privatiser] case class Public(reason: String) extends PrivatiserLevel {
  override def shouldReplace(aModel: ModelElement) = true

  def widen(level: PrivatiserLevel) = this

  override def asText(context: ModelElement): Option[String] = None
}

private[privatiser] case class NoChange(reason: String) extends PrivatiserLevel {
  override def shouldReplace(aModel: ModelElement) = false

  def widen(level: PrivatiserLevel) = this

  override def asText(context: ModelElement): Option[String] = None
}

private[privatiser] case object Undefined extends PrivatiserLevel {

  override def reason: String = "Initial"

  override def shouldReplace(aModel: ModelElement) = false

  def widen(level: PrivatiserLevel) = level

  override def asText(context: ModelElement): Option[String] = None

  override def marker(stat: Stat): Patch = Utils.addMarker(stat, "can't detect usage")
}

private[privatiser] object AccessScope {
  val None = AccessScope(Symbol.None, "")
}

private[privatiser] final case class AccessScope(symbol: Symbol, reason: String) {
  def print(name: String) = if (symbol.isNone) s"$name <not found>" else s"$name $symbol $reason"

  def widen(other: AccessScope) =
    if (symbol.isNone) other
    else if (other.symbol.isNone) this
    else AccessScope(SymbolUtils.findCommonParent(symbol, other.symbol), s"$reason AND ${other.reason}")
}

private[privatiser] object Scoped {
  def Private(scope: Symbol, reason: String) = Scoped(AccessScope(scope, reason), AccessScope.None, false)

  def Protected(scope: Symbol, reason: String, forceProtected: Boolean ) = Scoped(AccessScope.None, AccessScope(scope, reason), forceProtected)
}

private[privatiser] final case class Scoped(privateScope: AccessScope, protectedScope: AccessScope, forceProtected: Boolean ) extends PrivatiserLevel {
  def isProtected = {
    def commonParentScope =
      if (protectedScope.symbol.isNone) privateScope.symbol
      else SymbolUtils.findCommonParent(protectedScope.symbol, privateScope.symbol)

    forceProtected || privateScope.symbol.isNone || commonParentScope != privateScope.symbol
  }

  def scope: Symbol = privateScope.symbol
  def scopeOrDefault(default: Symbol): Symbol =  privateScope.symbol.asNonEmpty.getOrElse(default)
  override def asText(context: ModelElement): Option[String] = {
    val name = if (isProtected) "protected" else "private"
    context.enclosing.headOption match {
      case Some(enclosing) if scopeOrDefault(enclosing.symbol) == enclosing.symbol => Some(name)
      case _ => Some(s"$name[${privateScope.symbol.displayName}]")
    }
  }

  override def shouldReplace(aModel: ModelElement) = true

  override def toString = s"Scoped[${privateScope.print("private")} -- ${protectedScope.print("protected")}}"

  override def reason: String = {
    if (privateScope.symbol.isNone) protectedScope.reason
    else if (protectedScope.symbol.isNone) privateScope.reason
    else s"private due to (${privateScope.reason}), protected access from (${protectedScope.reason})"
  }

  def widen(level: PrivatiserLevel) = level match {
    case p: Public => p
    case n: NoChange => n
    case Undefined => this
    case other: Scoped =>
      val privateWidened = this.privateScope.widen(other.privateScope)
      if (privateWidened.symbol.isRootPackage) Public(privateWidened.reason)
      else Scoped(privateWidened,protectedScope.widen(other.protectedScope), forceProtected || other.forceProtected)
  }
}
