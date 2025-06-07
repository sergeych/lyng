# Classes

## Declaring

    class Foo1
    class Foo2() // same, empty constructor
    class Foo3() { // full
    }
    class Foo4 { // Only body
    }

```
class_declaration = ["abstract",] "class" [, constructor] [, body]
constructor = "(", [field [, field]] ")
field = [visibility ,] [access ,] name [, typedecl]
body = [visibility] ("var", vardecl) | ("val", vardecl) | ("fun", fundecl)
visibility = "private" | "protected" | "internal"
```

### Abstract classes

Contain one pr more abstract methods which must be implemented; though they
can have constructors, the instances of the abstract classes could not be
created independently