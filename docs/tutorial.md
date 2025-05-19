# Ling tutorial

Ling is a very simple language, where we take only most important and popular features from
other scripts and languages. In particular, we adopt _principle of minimal confusion_[^1].
In other word, the code usually works as expected when you see it. So, nothing unusual.

# Expressions and blocks.

Everything is an expression in Ling. Even an empty block:

    {
        // empty block
    }
    >>> void

Block returns it last expression as "return value":

    {
        2 + 2
        3 + 3
    }
    >>> 6

Same is without block:

    3 + 3
    >>> 6

If you don't want block to return anything, use `void`:

    {
        3 + 4
        void
    }
    >>> void

Every construction is an expression that returns something (or `void`):

    val limited = if( x > 100 ) 100 else x

You can use blocks in if statement, as expected:

    val limited = if( x > 100 ) {
        100 + x * 0.1    
    }
    else 
        x

So the principles are:

- everything is an expression returning its last calculated value or `void`
- expression could be a `{ block }`

## Expression details

It is rather simple, like everywhere else:

    sin(x * π/4) / 2.0

See [math](math.md) for more on it.

# Defining functions

    fun check(amount) {
        if( amount > 100 )
            "anough"
        else
            "more"
    }

You can use both `fn` and `fun`. Note that function declaration _is an expression returning callable_.

There are default parameters in Ling:

    fn check(amount, prefix = "answer: ") {
        prefix + if( amount > 100 )
            "anough"
        else
            "more" 
    }

## Closures

Each __block has an isolated context that can be accessed from closures__. For example:

    var counter = 1
    
    // this is ok: coumter is incremented
    def increment(amount=1) {
        // use counter from a closure:
        counter = counter + amount
    }

    val taskAlias = def someTask() {
        // this obscures global outer var with a local one
        var counter = 0
        // ...
        counter = 1
        // ...
        counter
    }

As was told, `def` statement return callable for the function, it could be used as a parameter, or elsewhere
to call it:

    // call the callable stored in the var
    taskAlias()
    // or directly:
    someTask()
    
If you need to create _unnamed_ function, use alternative syntax (TBD, like { -> } ?)

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

## String details

### String operations

Concatenation is a `+`: `"hello " + name` works as expected. No confusion.

### Literals

String literal could be multiline:

    "
        Hello,
        World!
    "
    >>> "Hello
    World"

In that case compiler removes left margin and first/last empty lines. Note that it won't remove margin:

    "Hello,
     World
    "
    >>> "Hello,
        World
       "

because the first line has no margin in the literal.

# Comments

    // single line comment
    var result = null // here we will store the result



