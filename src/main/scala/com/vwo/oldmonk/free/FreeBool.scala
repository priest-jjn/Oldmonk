package com.vwo.oldmonk.free

import scalaz._
import Scalaz._
import com.vwo.oldmonk.datastructures.CovariantSet
import spire.algebra.{Order => SpireOrder, _}
import spire.implicits._

trait FreeBoolAlgebra[F[_]] extends Applicative[F] with Functor[F] with Monad[F] with Traverse[F] {
  // A Free Boolean Algebra has a Bool[P]
  implicit def bool[A]: Bool[F[A]]

  /* For any function f: A => B, B a boolean algebra, there exists a natural homomorphism
   * from the free boolean algebra to the algebra of B.
   * It satisfies the property that nat(f)(x.point) = f(x)
   */
  def nat[A,B](f: A => B)(implicit ba: Bool[B]): F[A] => B

  override def map[A, B](fa: F[A])(f: A => B): F[B] = nat((a:A) => point[B](f(a)))(bool[B])(fa)
  def bind[A,B](fa: F[A])(f: A => F[B]): F[B] = nat((a:A) => f(a))(bool[B])(fa)


  def foldFunc[A,B](f: A => B, andHandler: List[B] => B, orHandler: List[B] => B, notHandler: B => B, trueVal: B, falseVal: B): F[A] => B

  def foldFreeBool[A,B](fa: F[A])(f: A => B, andHandler: List[B] => B, orHandler: List[B] => B, notHandler: B => B, trueVal: B, falseVal: B): B =
    foldFunc(f, andHandler, orHandler, notHandler, trueVal, falseVal)(fa)

  implicit def concrete[A]: ConcreteFreeBoolAlgebra[A,F] = new ConcreteFreeBoolAlgebra[A,F] {
    protected val bool = FreeBoolAlgebra.this.bool[A]
    def nat[B](f: A=>B)(implicit ba: Bool[B]) = FreeBoolAlgebra.this.nat[A,B](f)
    lazy val zero = bool.zero
    lazy val one = bool.one
    def and(a: F[A], b: F[A]) = bool.and(a,b)
    def complement(a: F[A]) = bool.complement(a)
    def or(a: F[A], b: F[A]) = bool.or(a,b)
  }
}

trait ConcreteFreeBoolAlgebra[A, F[_]] extends Bool[F[A]] {
  // This is used when some specific property of A is required to build a free bool
  // e.g., A might be required to be ordered
  // You'd then have:
  //   def concreteBool[A](implicit o: Order[A]): ConcreteFreeBoolAlgebra[A,F] = ???
  def nat[B](f: A => B)(implicit ba: Bool[B]): F[A] => B
}

private class FreeBoolUtil[P, F[_]](implicit a: ConcreteFreeBoolAlgebra[P,F]) {
  val truePred: F[P] = a.one
  val falsePred: F[P] = a.zero
  def andPred(terms: Seq[F[P]]) = terms.headOption.map(h =>
    terms.tail.foldLeft(h)( (x: F[P],y: F[P]) => x & y)
  ).getOrElse(a.one)
  def andPred(fst: F[P], ps: F[P]*) = ps.foldLeft(fst)( (x: F[P],y: F[P]) => x & y)
  def orPred(fst: F[P], ps: F[P]*) = ps.foldLeft(fst)( (x: F[P],y: F[P]) => x | y)
  def orPred(terms: Seq[F[P]]) = terms.headOption.map(h =>
    terms.tail.foldLeft(h)( (x: F[P],y: F[P]) => x | y)
  ).getOrElse(a.zero)
}

trait FreeBoolListInstances {
  // The simplest freebool instance - it doesn't make any attempt to simplify the objects.

  sealed trait FreeBoolList[+P]

  object FreeBoolList {
    /*
     * These traits are used to enforce some simplicity of expressions at the type level.
     * For example, AndPred(List(True, Pred(a), Pred(b))) is equivalent to AndPred(List(Pred(a), Pred(b)))
     * and AndPred(List(False, ...)) = False
     * So why represent these cases in the type system at all?
     */
    sealed trait NotAndPred
    sealed trait NotOrPred
    sealed trait NotNegated
    sealed trait CanVary

    sealed trait ConstantFreeBoolList extends FreeBoolList[Nothing] with NotAndPred with NotOrPred with NotNegated
    case object TruePred extends ConstantFreeBoolList
    case object FalsePred extends ConstantFreeBoolList

    case class Pred[+P](p: P) extends FreeBoolList[P] with NotOrPred with NotAndPred with NotNegated with CanVary
    case class Negate[+P](term: FreeBoolList[P] with NotNegated with CanVary) extends FreeBoolList[P] with NotAndPred with NotOrPred with CanVary
    case class AndPred[+P](terms: List[FreeBoolList[P] with NotAndPred with CanVary]) extends FreeBoolList[P] with NotOrPred with NotNegated with CanVary
    case class OrPred[+P](terms: List[FreeBoolList[P] with NotOrPred with CanVary]) extends FreeBoolList[P] with NotAndPred with NotNegated with CanVary
  }
  import FreeBoolList._

  object FreeBoolListAlgebra extends FreeBoolAlgebra[FreeBoolList] {
    def point[P](p: =>P): FreeBoolList[P] = Pred(p)

    def traverseImpl[G[_], A, B](fa: FreeBoolList[A])(f: A => G[B])(implicit ap: Applicative[G]): G[FreeBoolList[B]] = traverseImplMaterialized(fa)(f)(ap, new FreeBoolUtil[B,FreeBoolList])

    private def traverseImplMaterialized[G[_], A, B](fa: FreeBoolList[A])(f: A => G[B])(implicit ap: Applicative[G], syntax: FreeBoolUtil[B,FreeBoolList]): G[FreeBoolList[B]] = {
      import syntax._
      fa match {
        case Pred(u) => f(u).map(x => point[B](x))
        case Negate(term) => traverseImpl(term)(f).map(x => ~x)
        case (x:ConstantFreeBoolList) => (x:FreeBoolList[B]).point[G]
        case AndPred(terms) => terms.traverse(x => traverseImpl(x)(f)).map(tt => andPred(tt))
        case OrPred(terms) => terms.traverse(x => traverseImpl(x)(f)).map(tt => orPred(tt))
      }
    }

    override def bind[A, B](fa: FreeBoolList[A])(f: A => FreeBoolList[B]): FreeBoolList[B] = bindMaterialized(fa)(f)(new FreeBoolUtil[B,FreeBoolList])

    private def bindMaterialized[A, B](fa: FreeBoolList[A])(f: A => FreeBoolList[B])(implicit syntax: FreeBoolUtil[B,FreeBoolList]): FreeBoolList[B] = {
      import syntax._
      fa match {
        case TruePred => TruePred
        case FalsePred => FalsePred
        case Pred(x) => f(x)
        case Negate(x) => ~(bind(x)(f))
        case AndPred(terms) => andPred(terms.map(t => bind(t)(f)))
        case OrPred(terms) => orPred(terms.map(t => bind(t)(f)))
      }
    }

    override def map[A, B](fa: FreeBoolList[A])(f: A => B): FreeBoolList[B] = mapMaterialized(fa)(f)(new FreeBoolUtil[B,FreeBoolList])

    private def mapMaterialized[A,B](fa: FreeBoolList[A])(f: A => B)(implicit syntax: FreeBoolUtil[B,FreeBoolList]): FreeBoolList[B] = {
      import syntax._
      fa match {
        case TruePred => TruePred
        case FalsePred => FalsePred
        case Pred(x) => Pred(f(x))
        case Negate(x) => ~(map(x)(f))
        case AndPred(terms) => andPred(terms.map(t => map(t)(f)))
        case OrPred(terms) => orPred(terms.map(t => map(t)(f)))
      }
    }

    def foldFunc[A,B](f: A => B, andHandler: List[B] => B, orHandler: List[B] => B, notHandler: B => B, trueVal: B, falseVal: B): (FreeBoolList[A] => B) = {
      def folder(fa: FreeBoolList[A]): B = fa match {
        case TruePred => trueVal
        case FalsePred => falseVal
        case Pred(a) => f(a)
        case AndPred(aa) => andHandler(aa.map(folder))
        case OrPred(aa) => orHandler(aa.map(folder))
        case Negate(a) => notHandler(folder(a))
      }
      folder _
    }

    def bool[P] = new Bool[FreeBoolList[P]] {
      def and(a: FreeBoolList[P], b: FreeBoolList[P]) = (a,b) match {
        case (FalsePred, _) => FalsePred
        case (_, FalsePred) => FalsePred
        case (TruePred, x) => x
        case (x, TruePred) => x
        case (AndPred(terms1), AndPred(terms2)) => AndPred(terms1 ++ terms2)
        case (AndPred(terms), x:NotAndPred with CanVary) => AndPred(x :: terms)
        case (x:NotAndPred with CanVary, AndPred(terms)) => AndPred(x :: terms)
        case (x:NotAndPred with CanVary,y:NotAndPred with CanVary) => AndPred(List(x,y))
      }
      def or(a: FreeBoolList[P], b: FreeBoolList[P]) = (a,b) match {
        case (TruePred, _) => TruePred
        case (_, TruePred) => TruePred
        case (FalsePred, x) => x
        case (x, FalsePred) => x
        case (OrPred(terms1), OrPred(terms2)) => OrPred(terms1 ++ terms2)
        case (OrPred(terms), x:NotOrPred with CanVary) => OrPred(x :: terms)
        case (x:NotOrPred with CanVary, OrPred(terms)) => OrPred(x :: terms)
        case (x:NotOrPred with CanVary, y:NotOrPred with CanVary) => OrPred(List(x,y))
      }
      def complement(a: FreeBoolList[P]) = a match {
        case Negate(x) => x
        case FalsePred => TruePred
        case TruePred => FalsePred
        case (x:NotNegated with CanVary) => Negate(x)
      }
      def zero: FreeBoolList[P] = FalsePred: FreeBoolList[P]
      def one: FreeBoolList[P] = TruePred
    }

    def nat[A,B](f: A => B)(implicit ba: Bool[B]): (FreeBoolList[A] => B) = {
      def homo(a: FreeBoolList[A]): B = a match {
        case Pred(x) => f(x)
        case Negate(x) => ba.complement(homo(x))
        case AndPred(terms) => terms match {
          case (Nil) => ba.one
          case (h :: Nil) => homo(h)
          case (h :: t) => t.foldLeft(homo(h))( (x:B,y:FreeBoolList[A]) => ba.and(x, homo(y)) )
        }
        case OrPred(terms) => terms match {
          case (Nil) => ba.zero
          case (h :: Nil) => homo(h)
          case (h :: t) => t.foldLeft(homo(h))( (x:B,y:FreeBoolList[A]) => ba.or(x, homo(y)) )
        }
        case TruePred => ba.one
        case FalsePred => ba.zero
      }
      homo
    }

  implicit class FreeBoolListMethods2[A](a: FreeBoolList[A]) {
    def foldFreeBool[B](f: A => B, andHandler: List[B] => B, orHandler: List[B] => B, notHandler: B => B, trueVal: B, falseVal: B) = FreeBoolListAlgebra.foldFreeBool(a)(f, andHandler, orHandler, notHandler, trueVal, falseVal)
  }
  }
}

trait FreeBoolSimpleInstances {
  sealed trait FreeBoolSimple[+P]

  case object FreeBoolSimple {
    sealed trait NotAndPred
    sealed trait NotOrPred
    sealed trait NotNegated

    // True and false
    sealed trait ConstantFreeBoolSimple extends FreeBoolSimple[Nothing]
    case object TruePred extends ConstantFreeBoolSimple with NotAndPred with NotOrPred with NotNegated
    case object FalsePred extends ConstantFreeBoolSimple with NotAndPred with NotOrPred with NotNegated

    case class Pred[+P](p: P) extends FreeBoolSimple[P] with NotAndPred with NotOrPred with NotNegated
    case class Negate[+P](term: FreeBoolSimple[P] with NotNegated) extends FreeBoolSimple[P] with NotAndPred with NotOrPred
    case class AndPred[+P](terms: CovariantSet[FreeBoolSimple[P] with NotAndPred]) extends FreeBoolSimple[P] with NotOrPred with NotNegated
    case class OrPred[+P](terms: CovariantSet[FreeBoolSimple[P] with NotOrPred]) extends FreeBoolSimple[P] with NotAndPred with NotNegated
  }
  import FreeBoolSimple._

  private implicit def FreeBoolSimpleOrder[P](implicit o: Order[P]) = new Order[FreeBoolSimple[P]] {
    import scalaz.Ordering._

    def order(x: FreeBoolSimple[P], y: FreeBoolSimple[P]): Ordering = (x,y) match {
      case (FalsePred, FalsePred) => EQ
      case (FalsePred, TruePred) => LT
      case (TruePred, FalsePred) => GT
      case (x:ConstantFreeBoolSimple, _) => LT
      case (_, x:ConstantFreeBoolSimple) => LT
      case (Pred(p1), Pred(p2)) => o.order(p1, p2)
      case (Pred(_), _) => LT
      case (_, Pred(_)) => GT
      case (Negate(f1), Negate(f2)) => order(f1, f2)
      case (Negate(_), _) => LT
      case (_, Negate(_)) => GT
      case (AndPred(x), AndPred(y)) => {
        if (x.size > y.size) {
          GT
        } else if (x.size < y.size) {
          LT
        } else {
          val pairs = x.toList.zip(y.toList).iterator
          var result: Ordering = EQ
          while (pairs.hasNext && (result != EQ)) {
            val t: (FreeBoolSimple[P], FreeBoolSimple[P]) = pairs.next
            result = order(t._1, t._2)
          }
          result
        }
      }
      case (AndPred(_), _) => LT
      case (_, AndPred(_)) => GT
      case (OrPred(x), OrPred(y)) => {
        if (x.size > y.size) {
          GT
        } else if (x.size < y.size) {
          LT
        } else {
          val pairs = x.toList.zip(y.toList).iterator
          var result: Ordering = EQ
          while (pairs.hasNext && (result != EQ)) {
            val t: (FreeBoolSimple[P], FreeBoolSimple[P]) = pairs.next
            result = order(t._1, t._2)
          }
          result
        }
      }
    }
  }

  def FreeBoolSimpleAlgebra[P](implicit o: Order[P]) = new ConcreteFreeBoolAlgebra[P, FreeBoolSimple] {
    private implicit val FreeBoolOrder = FreeBoolSimpleOrder[P]

    private implicit val FreeBoolOrderNotAnd: Order[FreeBoolSimple[P] with NotAndPred] = new Order[FreeBoolSimple[P] with NotAndPred] {
      def order(x: FreeBoolSimple[P] with NotAndPred, y: FreeBoolSimple[P] with NotAndPred) = FreeBoolOrder.order(x,y)
    }

    private implicit val FreeBoolOrderNotOr: Order[FreeBoolSimple[P] with NotOrPred] = new Order[FreeBoolSimple[P] with NotOrPred] {
      def order(x: FreeBoolSimple[P] with NotOrPred, y: FreeBoolSimple[P] with NotOrPred) = FreeBoolOrder.order(x,y)
    }

    def and(a: FreeBoolSimple[P], b: FreeBoolSimple[P]) = (a,b) match {
      case (FalsePred, _) => FalsePred
      case (_, FalsePred) => FalsePred
      case (TruePred, x:AndPred[P]) => x
      case (x: AndPred[P], TruePred) => x
      case (AndPred(terms1), AndPred(terms2)) => AndPred(terms1 ++ terms2)
      case (AndPred(terms), (x: NotAndPred)) => AndPred(terms.insert(x))
      case (x:NotAndPred, AndPred(terms)) => AndPred(terms.insert(x))
      case (x:NotAndPred,y:NotAndPred) => AndPred(CovariantSet(x,y))
    }
    def or(a: FreeBoolSimple[P], b: FreeBoolSimple[P]) = (a,b) match {
      case (TruePred, _) => TruePred
      case (_, TruePred) => TruePred
      case (FalsePred, x:AndPred[P]) => x
      case (x: OrPred[P], FalsePred) => x
      case (OrPred(terms1), OrPred(terms2)) => OrPred(terms1 ++ terms2)
      case (OrPred(terms), (x:NotOrPred)) => OrPred(terms.insert(x))
      case (x:NotOrPred, OrPred(terms)) => OrPred(terms.insert(x))
      case (x:NotOrPred,y:NotOrPred) => OrPred(CovariantSet(x,y))
    }
    def complement(a: FreeBoolSimple[P]) = a match {
      case Negate(x) => x
      case FalsePred => TruePred
      case TruePred => FalsePred
      case (x:NotNegated) => Negate(x)
    }
    def zero: FreeBoolSimple[P] = FalsePred: FreeBoolSimple[P]
    def one: FreeBoolSimple[P] = TruePred

    def nat[B](f: P => B)(implicit ba: Bool[B]): (FreeBoolSimple[P] => B) = {
      /* For any function f: A => B, B a boolean algebra, there exists a natural homomorphism
       * from the free boolean algebra to the algebra of B.
       */
      def homo(a: FreeBoolSimple[P]): B = a match {
        case Pred(x) => f(x)
        case Negate(x) => ba.complement(homo(x))
        case AndPred(terms) => terms.foldLeft(ba.one)( (x:B,y:FreeBoolSimple[P]) => ba.and(x, homo(y)) )
        case OrPred(terms) => terms.foldLeft(ba.zero)( (x:B,y:FreeBoolSimple[P]) => ba.or(x, homo(y)) )
        case TruePred => ba.one
        case FalsePred => ba.zero
      }
      homo
    }
  }
}
