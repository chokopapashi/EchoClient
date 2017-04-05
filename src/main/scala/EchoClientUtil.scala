
case class EchoClientName(n: Int) {
    val _name = EchoClientName.name(n)
    override def toString = _name
}
object EchoClientName {
    def name(n: Int) = f"Echo$n%03d"
}

