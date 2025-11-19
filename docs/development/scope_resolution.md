[//]: # (excludeFromIndex)


Provide:


    fun outer(a1)
        // a1 is caller.a1:arg
        val a1_local = a1 + 1
        // we return lambda:
        { it ->
            // a1_local
            a1_lcoal + it
        }
    }