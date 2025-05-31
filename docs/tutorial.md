# Ling tutorial

Ling is a very simple language, where we take only most important and popular features from
other scripts and languages. In particular, we adopt _principle of minimal confusion_[^1].
In other word, the code usually works as expected when you see it. So, nothing unusual.

__Other documents to read__ maybe after this one:

- [Advanced topics](advanced_topics.md)
- [OOP notes](OOP.md)
- [math in Ling](math.md)
- Some class references: [List](List.md), [Real](Real.md), [Range](Range.md)

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
allow chained assignments (we might fix it later). Use parentheses insofar:

    var x = 0
    var y = 0
    x = (y = 5)
    assert(x==5)
    assert(y==5)
    >>> void

Note that assignment operator returns rvalue, it can't be assigned.

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

These operators return rvalue, unmodifiable.

## Assignment return r-value!

## Math

It is rather simple, like everywhere else:

    val x = 2.0
    sin(x * π/4) / 2.0
    >>> 0.5

See [math](math.md) for more on it. Notice using Greek as identifier, all languages are allowed.

Logical operation could be used the same

    var x = 10
    ++x >= 11
    >>> true

## Supported operators

|    op    | ass | args              | comments |
|:--------:|-----|-------------------|----------|
|    +     | +=  | Int or Real       |          |
|    -     | -=  | Int or Real       | infix    |
|    *     | *=  | Int or Real       |          |
|    /     | /=  | Int or Real       |          |
|    %     | %=  | Int or Real       |          |
|    &&    |     | Bool              |          |
|   \|\|   |     | Bool              |          |   
|    !x    |     | Bool              |          |
|    <     |     | String, Int, Real | (1)      |
|    <=    |     | String, Int, Real | (1)      |
|    >=    |     | String, Int, Real | (1)      |
|    >     |     | String, Int, Real | (1)      |
|    ==    |     | Any               | (1)      |
|   ===    |     | Any               | (2)      |
|   !==    |     | Any               | (2)      |
|    !=    |     | Any               | (1)      |
| ++a, a++ |     | Int               |          |
| --a, a-- |     | Int               |          |

(1)
: comparison are based on comparison operator which can be overloaded

(2)
: referential equality means left and right operands references exactly same instance of some object. Note that all
singleton object, like `null`, are referentially equal too, while string different literals even being equal are most
likely referentially not equal

Reference quality and object equality example:

    assert( null == null)  // singletons
    assert( null === null)
    // but, for non-singletons:
    assert( 5 == 5)
    assert( 5 !== 5)
    assert( "foo" !== "foo" )
    >>> void

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

This is though a rare case when you need uninitialized variables, most often you can use conditional operators
and even loops to assign results (see below).

# Constants

Almost the same, using `val`:

    val foo = 1
    foo += 1 // this will throw exception

# Constants

Same as in kotlin:

    val HalfPi = π / 2

Note using greek characters in identifiers! All letters allowed, but remember who might try to read your script, most
likely will know some English, the rest is the pure uncertainty.

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
    assert( "do: more" == check(10, "do: ") )
    check(120)
    >>> "answer: enough"

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

# Lists (aka arrays)

Ling has built-in mutable array class `List` with simple literals:

    [1, "two", 3.33].size
    >>> 3

Lists can contain any type of objects, lists too:

    val list = [1, [2, 3], 4]
    assert(list.size == 3)
    // second element is a list too:
    assert(list[1].size == 2)
    >>> void

Notice usage of indexing. You can use negative indexes to offset from the end of the list; see more in [Lists](List.md).

When you want to "flatten" it to single array, you can use splat syntax:

    [1, ...[2,3], 4]
    >>> [1, 2, 3, 4]

Of course, you can splat from anything that is List (or list-like, but it will be defined later):

    val a = ["one", "two"]
    val b = [10.1, 20.2]
    ["start", ...b, ...a, "end"]
    >>> ["start", 10.1, 20.2, "one", "two", "end"]

Of course, you can set any list element:

    val a = [1, 2, 3]
    a[1] = 200
    a
    >>> [1, 200, 3]

Lists are comparable, as long as their respective elements are:

    assert( [1,2,3] == [1,2,3])
    
    // but they are _different_ objects:
    assert( [1,2,3] !== [1,2,3])
    
    // when sizes are different, but common part is equal,
    // longer is greater
    assert( [1,2,3] > [1,2] )

    // otherwise, where the common part is greater, the list is greater:
    assert( [1,2,3] < [1,3] )
    >>> void

All comparison operators with list are working ok. Also, you can concatenate lists:

    assert( [5, 4] + ["foo", 2] == [5, 4, "foo", 2])
    >>> void

To add elements to the list:

    val x = [1,2]
    x.add(3)
    assert( x == [1,2,3])
    // same as x += ["the", "end"] but faster:
    x.add("the", "end")
    assert( x == [1, 2, 3, "the", "end"])
    >>> void

Self-modifying concatenation by `+=` also works:

    val x = [1, 2]
    x += [3, 4]
    assert( x == [1, 2, 3, 4])
    >>> void

You can insert elements at any position using `addAt`:

    val x = [1,2,3]
    x.addAt(1, "foo", "bar")
    assert( x == [1, "foo", "bar", 2, 3])
    >>> void

Using splat arguments can simplify inserting list in list:

    val x = [1, 2, 3]
    x.addAt( 1, ...[0,100,0])
    x
    >>> [1, 0, 100, 0, 2, 3]

Using negative indexes can insert elements as offset from the end, for example:

    val x = [1,2,3]
    x.addAt(-1, 10)
    x
    >>> [1, 2, 10, 3]

Note that to add to the end you still need to use `add` or positive index of the after-last element:

    val x = [1,2,3]
    x.addAt(3, 10)
    x
    >>> [1, 2, 3, 10]

## Removing list items

    val x = [1, 2, 3, 4, 5]
    x.removeAt(2)
    assert( x == [1, 2, 4, 5])
    // or remove range (start inclusive, end exclusive):
    x.removeRangeInclusive(1,2)    
    assert( x == [1, 5])
    >>> void

Again, you can use negative indexes. For example, removing last elements like:

    val x = [1, 2, 3, 4, 5]

    // remove last:
    x.removeAt(-1)
    assert( x == [1, 2, 3, 4])
    
    // remove 2 last:
    x.removeRangeInclusive(-2,-1)
    assert( x == [1, 2])
    >>> void

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

Why `void`? Because `break` drops out without the chute, not providing anything to return. Indeed, we should provide
exit value in the case:

    var count = 0
    while( count < 50 ) {
        if( count > 3 ) break "too much"
        count = ++count * 10
        "wrong "+count
    }
    >>> "too much"

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
    >>> "5/2 situation"

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
    >>> "found even numbers: 5"

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

Notice that `total` remains 0 as the end of the outerLoop@ is not reachable: `continue` is always called and always make
Ling to skip it.

## else statement

The while and for loops can be followed by the else block, which is executed when the loop
ends normally, without breaks. It allows override loop result value, for example,
to not calculate it in every iteration. See for loop example just below.

## For loops

For loop are intended to traverse collections, and all other objects that supports
size and index access, like lists:

    var letters = 0
    for( w in ["hello", "wolrd"]) {
        letters += w.length
    }
    "total letters: "+letters
    >>> "total letters: 10"

For loop support breaks the same as while loops above:

    fun search(haystack, needle) {    
        for(ch in haystack) {
            if( ch == needle) 
                break "found"
        }
        else null
    }
    assert( search("hello", 'l') == "found")
    assert( search("hello", 'z') == null)
    >>> void

We can use labels too:

    fun search(haystacks, needle) {    
        exit@ for( hs in haystacks ) {
                for(ch in hs ) {
                    if( ch == needle) 
                        break@exit "found"
                }
            }
            else null
    }
    assert( search(["hello", "world"], 'l') == "found")
    assert( search(["hello", "world"], 'z') == null)
    >>> void

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

# Ranges

Ranges are convenient to represent the interval between two values:

    5 in (0..100)
    >>> true

It could be open and closed:

    assert( 5 in (1..5) )
    assert( 5 !in (1..<5) )
    >>> void

Ranges could be inside other ranges:

    assert( (2..3) in (1..10) )
    >>> void

There are character ranges too:

    'd' in 'a'..'e'
    >>> true

and you can use ranges in for-loops:

    for( x in 'a' ..< 'c' ) println(x)
    >>> a
    >>> b
    >>> void

See [Ranges](Range.md) for detailed documentation on it.

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
| Char   | single unicode character        | `'S'`, `'\n'`       |
| String | unicode string, no limits       | "hello" (see below) |
| List   | mutable list                    | [1, "two", 3]       |
| Void   | no value could exist, singleton | void                |
| Null   | missing value, singleton        | null                |
| Fn     | callable type                   |                     |

See also [math operations](math.md)

## Character details

The type for the character objects is `Char`.

### Char literal escapes

Are the same as in string literals with little difference:

| escape | ASCII value       |
|--------|-------------------|
| \n     | 0x10, newline     |
| \t     | 0x07, tabulation  |
| \\     | \ slash character |
| \'     | ' apostrophe      |

### Char instance members

    assert( 'a'.code == 0x61 ) 
    >>> void

| member | type | meaning                        |
|--------|------|--------------------------------|
| code   | Int  | Unicode code for the character |
|        |      |                                |


## String details

### String operations

Concatenation is a `+`: `"hello " + name` works as expected. No confusion.

### Literals

String literal could be multiline:

    "Hello
    World"

though multiline literals is yet work in progress.

# Built-in functions

See [math functions](math.md). Other general purpose functions are:

| name                                         | description                                              |
|----------------------------------------------|----------------------------------------------------------|
| assert(condition,message="assertion failed") | runtime code check. There will be an option to skip them |
| println(args...)                             | Open for overriding, it prints to stdout.                |

# Built-in constants

| name                                | description                  |
|-------------------------------------|------------------------------|
| Real, Int, List, String, List, Bool | Class types for real numbers |
| π                                   | See [math](math.md)          |




