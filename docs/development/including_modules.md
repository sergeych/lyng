# Modules inclusion

Module is, at the low level, a statement that modifies a given context by adding
here local and exported symbols, performing some tasks and even returning some value
we don't need for now.

The compiled module is therefore a statement. When we execute it on some context,
it wills it with all it symbols, private too.

If we call it on another context, it will do it once more, no caching. This is unnecessary
if not dangerous repetition.

## What is inclusion?

The _goal_ of the inclusion is to make _exported symbols_ available in a given context,
without re-executing included module initialization code. So, when we hit the `import foo`,
we should check that foo module was executed, execute it if not on special context we store in the library, then copy all public symbols from fooContext into current one.

## Class pseudo-module

Mostly same we can do with a class

## Module initialization

We can just put the code into the module code:

    module lying.samples.module
    // or package?
    
    val startuptTime = Instant.now()

    // private: not available from outside
    private fun moduleInitialization() {
        // long code
    }

    // this will be called only once
    moduleInitialization()

## class initialization

    class foo {
        
        private static var instanceCounter = 0
        
        val instanceId = instanceCounter
        
        fun close() {
            instanceCounter--
        }

        // instance initializatino could be as this:
        if( instanceId > 100 )
            throw Exception("Too many instances")

        static {
            // static, one-per-class initializer could be posted here
            instanceCounter = 1
        }
    }
