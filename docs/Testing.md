# Testing and Assertions

Lyng provides several built-in functions for testing and verifying code behavior. These are available in all scripts.

## Basic Assertions

### `assert`

Assert that a condition is true.

    assert(condition, message=null)

- `condition`: A boolean expression.
- `message` (optional): A string message to include in the exception if the assertion fails.

If the condition is false, it throws an `AssertionFailedException`.

```lyng
assert(1 + 1 == 2)
assert(true, "This should be true")
```

### `assertEquals` and `assertEqual`

Assert that two values are equal. `assertEqual` is an alias for `assertEquals`.

    assertEquals(expected, actual)
    assertEqual(expected, actual)

If `expected != actual`, it throws an `AssertionFailedException` with a message showing both values.

```lyng
assertEquals(4, 2 * 2)
assertEqual("hello", "hel" + "lo")
```

### `assertNotEquals`

Assert that two values are not equal.

    assertNotEquals(unexpected, actual)

If `unexpected == actual`, it throws an `AssertionFailedException`.

```lyng
assertNotEquals(5, 2 * 2)
```

## Exception Testing

### `assertThrows`

Assert that a block of code throws an exception.

    assertThrows(code)
    assertThrows(expectedExceptionClass, code)

- `expectedExceptionClass` (optional): The class of the exception that is expected to be thrown.
- `code`: A lambda block or statement to execute.

If the code does not throw an exception, an `AssertionFailedException` is raised. 
If an `expectedExceptionClass` is provided, the thrown exception must be of that class (or its subclass), otherwise an error is raised.

`assertThrows` returns the caught exception object if successful.

```lyng
// Just assert that something is thrown
assertThrows { 1 / 0 }

// Assert that a specific exception class is thrown
assertThrows(NoSuchElementException) { 
    [1, 2, 3].findFirst { it > 10 } 
}

// You can use the returned exception
val ex = assertThrows { throw Exception("custom error") }
assertEquals("custom error", ex.message)
```

## Other Validation Functions

While not strictly for testing, these functions help in defensive programming:

### `require`

    require(condition, message="requirement not met")

Throws an `IllegalArgumentException` if the condition is false. Use this for validating function arguments.

If we want to evaluate the message lazily:

    require(condition) { "requirement not met: %s"(someData) }

In this case, formatting will only occur if the condition is not met.

### `check`

    check(condition, message="check failed")

Throws an `IllegalStateException` if the condition is false. Use this for validating internal state.

With lazy message evaluation:

    check(condition) { "check failed: %s"(someData) }

In this case, formatting will only occur if the condition is not met.

### TODO

It is easy to mark some code and make it throw a special exception at cone with:

    TODO()

or

    TODO("some message")

It raises an `NotImplementedException` with the given message. You can catch it
as any other exception when necessary.

Many IDE and editors have built-in support for marking code with TODOs.
