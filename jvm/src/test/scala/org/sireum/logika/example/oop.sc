// #Sireum #Logika
import org.sireum._

@sig trait AParent2[T] {
  @spec var x: T = $
  @spec var y: Z = $

//  @spec def yNonNegative = Invariant(
//    y >= 0
//  )
}

@sig trait AParent extends AParent2[Z] {
  @spec var z: Z = $
}

@datatype class A extends AParent

object B {
  var inc: Z = 1

//  @spec def incPos = Invariant(
//    inc > 0
//  )

  def compute(a: A): Unit = {
    Contract(
      Modifies(a),
      Ensures(
        a.x == In(a).x + 1 /* inc */,
        a.y == In(a).y + a.x,
        a.z == In(a).z,
        a == In(a)
      )
    )
    Spec {
      a.x = a.x + 1
      a.y = a.y + a.x
    }
  }
}

def foo(az: A): Unit = {
  Contract(
    Modifies(az),
    Ensures(
      az.x > In(az).x,
      az.y == In(az).y + az.x,
      az.z == In(az).z,
      az == In(az)
    )
  )
  B.compute(az)
}