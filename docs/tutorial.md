# Ling tutorial

Ling is a very simple language, where we take only most important and popular features from
other scripts and languages. In particular, we adopt _principle of minimal confusion_[^1].
In other word, the code usually works as expected when you see it. So, nothing unusual.

# Expressions

Everything is an expression in Ling. Even an empty block:

    // empty block
    >>> void

any block also returns it's last expression:

    if( true ) {
        2 + 2
        3 + 3
    }
    >>> 6

If you don't want block to return anything, use `void`:

    fn voidFunction() {
        3 + 4 // this will be ignored
        void
    }
    voidFunction()
    >>> void

otherwise, last expression will be returned:
    
    fn normalize(value, minValue, maxValue) {
        (value - minValue) / (maxValue-minValue)
    }
    normalize( 4, 0.0, 10.0)
    >>> 0.4

Every construction is an expression that returns something (or `void`):

    val x = 111 // or autotest will fail!
    val limited = if( x > 100 ) 100 else x
    limited
    >>> 100

You can use blocks in if statement, as expected:

    val x = 200
    val limited = if( x > 100 ) {
        100 + x * 0.1    
    }
    else 
        x
    limited
    >>> 120.0

When putting multiple statments in the same line it is convenient and recommended to use `;`:

    var from; var to
    from = 0; to = 100
    >>> 100

Notice: returned value is `100` as assignment operator returns its assigned value.
Most often you can omit `;`, but improves readability and prevent some hardly seen bugs.

## Assignments

Assignemnt is an expression that changes its lvalue and return assigned value:

    var x = 100
    x = 20
    println(5 + (x=6)) // 11: x changes its value!
    x
    >>> 11
    >>> 6

As the assignment itself is an expression, you can use it in strange ways. Just remember
to use parentheses as assignment operation insofar is left-associated and will not 
allow chained assignments (we might fix it later)

    var x = 0
    var y = 0
    x = (y = 5)
    x + y
    >>> 10

## Modifying arithmetics

There is a set of assigning operations: `+=`, `-=`, `*=`, `/=` and even `%=`. 

    var x = 5
    assert( 25 == (x*=5) )
    assert( 25 == x)
    assert( 24 == (x-=1) )
    assert( 12 == (x/=2) )
    x
    >>> 12

Notice the parentheses here: the assignment has low priority!

## Expression details

It is rather simple, like everywhere else:

    val x = 2.0
    //
    sin(x * π/4) / 2.0
    >>> 0.5

See [math](math.md) for more on it. Notice using Greek as identifier, all languages are allowed.

# Variables

Much like in kotlin, there are _variables_:

    var name = "Sergey"

Variables can be not initialized at declaration, in which case they must be assigned before use, or an exception
will be thrown:

    var foo
    // WRONG! Exception will be thrown at next line:
    foo + "bar"

Correct pattern is:

    foo = "foo"
    // now is OK:
    foo + bar

This is though a rare case when you need uninitialized variables, most often you can use conditional operatorss
and even loops to assign results (see below).

# Constants

Same as in kotlin:

    val HalfPi = π / 2

Note using greek characters in identifiers! All letters allowed, but remember who might try to read your script, most likely will know some English, the rest is the pure uncertainty.

# Defining functions

    fun check(amount) {
        if( amount > 100 )
            "enough"
        else
            "more"
    }
    >>> Callable@...

Notice how function definition return a value, instance of `Callable`. 

You can use both `fn` and `fun`. Note that function declaration _is an expression returning callable_.

There are default parameters in Ling:

    fn check(amount, prefix = "answer: ") {
        prefix + if( amount > 100 )
            "enough"
        else
            "more" 
    }
    >>> Callable@...

## Closures

Each __block has an isolated context that can be accessed from closures__. For example:

    var counter = 1

    // this is ok: coumter is incremented
    fun increment(amount=1) {
        // use counter from a closure:
        counter = counter + amount
    }

    val taskAlias = fun someTask() {
        // this obscures global outer var with a local one
        var counter = 0
        // ...
        counter = 1
        // ...
        counter
    }
    >>> void

As was told, `fun` statement return callable for the function, it could be used as a parameter, or elsewhere
to call it:

    val taskAlias = fun someTask() {
        println("Hello")
    }
    // call the callable stored in the var
    taskAlias()
    // or directly:
    someTask()
    >>> Hello
    >>> Hello
    >>> void

If you need to create _unnamed_ function, use alternative syntax (TBD, like { -> } ?)

# Flow control operators

## if-then-else

As everywhere else, and as expression:

    val count = 11
    if( count > 10 )
        println("too much")
    else {
        // do something else
        println("just "+count)
    }
    >>> too much
    >>> void

Notice returned value `void`: it is because of `println` have no return value, e.g., `void`.


Or, more neat:

    var count = 3
    println( if( count > 10 ) "too much" else "just " + count )
    >>> just 3
    >>> void

## while 

Regular pre-condition while loop, as expression, loop returns it's last line result:

    var count = 0
    while( count < 5 ) {
        count++
        count * 10
    }
    >>> 50

We can break as usual:

    var count = 0
    while( count < 5 ) {
        if( count < 5 ) break
        count = ++count * 10
    }
    >>> void

Why `void`? Because `break` drops out without the chute, not providing anything to return. Indeed, we should provide exit value in the case:

    var count = 0
    while( count < 50 ) {
        if( count > 3 ) break "too much"
        count = ++count * 10
        "wrong "+count
    }
    >>> too much

### Breaking nested loops

If you have several loops and want to exit not the inner one, use labels:

    var count = 0
    // notice the label:
    outerLoop@ while( count < 5 ) {
        var innerCount = 0
        while( innerCount < 100 ) {
            innerCount = innerCount + 1

            if( innerCount == 5 && count == 2 )
                // and here we break the labelled loop:
                break@outerLoop "5/2 situation"
        }
        count = count + 1
        count * 10
    }
    >>> 5/2 situation

### and continue

We can skip the rest of the loop and restart it, as usual, with `continue` operator.

    var count = 0
    var countEven = 0
    while( count < 10 ) {
        count = count + 1
        if( count % 2 == 1) continue
        countEven = countEven + 1
    }
    "found even numbers: " + countEven
    >>> found even numbers: 5

`continue` can't "return" anything: it just restarts the loop. It can use labeled loops to restart outer ones:

    var count = 0
    var total = 0
    // notice the label:
    outerLoop@ while( count < 5 ) {
        count = count + 1
        var innerCount = 0
        while( innerCount < 10 ) {
            innerCount = innerCount + 1
            if( innerCount == 10 )
                continue@outerLoop
        }
        // we don't reach it because continue above restarts our loop
        total = total + 1
    }
    total
    >>> 0

Notice that `total` remains 0 as the end of the outerLoop@ is not reachable: `continue` is always called and always make Ling to skip it.

## Labels@

The label can be any valid identifier, even a keyword, labels exist in their own, isolated world, so no risk of occasional clash. Labels are also scoped to their context and do not exist outside it.

Right now labels are implemented only for the while loop. It is intended to be implemented for all loops and returns.

# Self-assignments in expression

There are auto-increments and auto-decrements:

    var counter = 0
    assert(counter++ * 100 == 0)
    assert(counter == 1)
    >>> void
    
but:

    var counter = 0
    assert( ++counter * 100 == 100)
    assert(counter == 1)
    >>> void

The same with `--`:

    var count = 100
    var sum = 0
    while( count > 0 ) sum = sum + count--
    sum
    >>> 5050

There are self-assigning version for operators too:

    var count = 100
    var sum = 0
    while( count > 0 ) sum += count--
    sum
    >>> 5050

# Comments

    // single line comment
    var result = null // here we will store the result
    >>> void

# Integral data types

| type   | description                     | literal samples     |
|--------|---------------------------------|---------------------|
| Int    | 64 bit signed                   | `1` `-22` `0x1FF`   |
| Real   | 64 bit double                   | `1.0`, `2e-11`      |
| Bool   | boolean                         | `true` `false`      |
| String | unicode string, no limits       | "hello" (see below) |
| Void   | no value could exist, singleton | void                |
| Null   | missing value, singleton        | null                |
| Fn     | callable type                   |                     |

See also [math operations](math.md)

## String details

### String operations

Concatenation is a `+`: `"hello " + name` works as expected. No confusion.

### Literals

String literal could be multiline:

    "Hello
    World"

though multiline literals is yet work in progress.

# Built-in functions

See [math functions](math.md), and:

| name                                         | description                                              |
|----------------------------------------------|----------------------------------------------------------|
| assert(condition,message="assertion failed") | runtime code check. There will be an option to skip them |
|                                              |                                                          |
|                                              |                                                          |
|                                              |                                                          |





