# Exceptions handling

Exceptions are widely used in modern programming languages, so
they are implemented also in Lyng and in the most complete way.

# Exception classes

Exceptions are throwing instances of some class that inherits `Exception`
across the code. Below is the list of built-in exceptions. Note that
only objects that inherit `Exception` can be thrown. For example:

    assert( IllegalArgumentException() is Exception)
    >>> void

# Try statement: catching exceptions

There general pattern is:

```
try_statement = try_clause, [catch_clause, ...], [finally_clause] 

try_clause = "try", "{", statements, "}"

catch_clause = "catch", [(full_catch | shorter_catch)], "{", statements "}"

full_catch = "(", catch_var, ":", exception_class [, excetpion_class...], ")

shorter_catch = "(", catch_var, ")"

finally_clause = "{", statements, "}"
```

Let's in details.

## Full catch block:

    val result = try {
        throw IllegalArgumentException("the test")
    }
    catch( x: IndexOutOfBoundsException, IllegalArgumentException) {
        x.message
    }
    catch(x: Exception) {
        "bad"
    }
    assertEquals(result, "the test")
    >>> void

Because our exception is listed in a first catch block, it is processed there.

The full form allow a single catch block to process exceptions with specified classes and bind actual caught object to
the given variable. This is most common and well known form, implemented like this or similar in many other languages,
like Kotlin, Java or C++.

## Shorter form

When you want to catch _all_ the exceptions, you should write `catch(e: Exception)`,
but it is somewhat redundant, so there is simpler variant:

    val sample2 = try {
        throw IllegalArgumentException("sample 2")
    }
    catch(x) {
        x.message
    }
    assertEquals( sample2, "sample 2" )
    >>> void

But well most likely you will find default variable `it`, like in Kotlin, more than enough
to catch all exceptions to, then you can write it even shorter:

    val sample2 = try {
        throw IllegalArgumentException("sample 3")
    }
    catch {
        it.message
    }
    assertEquals( sample2, "sample 3" )
    >>> void

You can even check the type of the `it` and create more convenient and sophisticated processing logic. Such approach is
used, for example, in Scala.

## finally block

If `finally` block present, it will be executed after body (until first exception)
and catch block, if any will match. finally statement is executed even if the
exception will be thrown and not caught locally. It does not alter try/catch block result:

    try {
    }
    finally {
        println("called finally")
    }
    >>> called finally
    >>> void

- and yes, there could be try-finally block, no catching, but perform some guaranteed cleanup.

# Conveying data with exceptions

The simplest way is to provide exception string and `Exception` class:

    try {
        throw Exception("this is my exception")
    }
    catch {
        it.message
    }
    >>> "this is my exception"

This way, in turn, can also be shortened, as it is overly popular:

    try {
        throw "this is my exception"
    }
    catch {
        it.message
    }
    >>> "this is my exception"

The trick, though, works with strings only, and always provide `Exception` instances, which is good for debugging but
most often not enough.

# Custom error classes

_this functionality is not yet released_

# Standard exception classes

| class                      | notes                                                 |
|----------------------------|-------------------------------------------------------|
| Exception                  | root of al throwable objects                          |
| NullReferenceException       |                                                       |
| AssertionFailedException   |                                                       | 
| ClassCastException         |                                                       |
| IndexOutOfBoundsException  |                                                       |
| IllegalArgumentException   |                                                       | 
| IllegalAssignmentException | assigning to val, etc.                                |
| SymbolNotDefinedException  |                                                       |
| IterationEndException      | attempt to read iterator past end, `hasNext == false` |
| AccessException            | attempt to access private members or like             |
| UnknownException           | unexpected kotlin exception caught                    |
|                            |                                                       |

