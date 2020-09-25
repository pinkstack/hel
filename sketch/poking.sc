type Name = String
type Mail = String

case class Person(name: Name, mail: Mail)

val people: List[Person] = List(
  Person("Oto", "otobrglez@gmail.com"),
  Person("Jernej", "gretzky@gmail.com")
)

def pimpMyName(person: Person): Person = {
  person.copy(name = person.name.toUpperCase)
}

val pimpMail: Person => Person = p => p.copy(mail = p.mail.toUpperCase)

def getFolk: List[Person] = people
  .sortBy(_.name.length)
  .map(p => pimpMyName(p))
  .map(pimpMail)
  .filterNot(_.mail.startsWith("OTO"))

def findByMail(mail: String) = getFolk.find(_.mail == mail)

def findByName(name: String) = getFolk.find(_.name == name)

def findByMailOrName(s: String): Option[Person] = {
  findByMail(s) match {
    case Some(p) => Some(p)
    case None =>
      findByName(s) match {
        case Some(p) => Some(p)
        case None => None
      }
  }
}

def findByMailOrName2(s: String) = {
  findByMail(s).orElse(findByName(s))
}


val person: Option[Person] =
  getFolk.find(_.mail == "dodo@gmail.com")


person match {
  case Some(person) => println(s"oooo hello ${person}")
  case None => println("bodoy here.")
}

