# Sample combinatorics calculations

The trivial to start with, the factorialorial calculation:

    fun factorial(n) {
        if( n < 1 )
            1
        else {
            var result = 1
            var cnt = 2
            while( cnt <= n ) result = result * cnt++
        }
    }

Let's test it:
    
    assert(factorial(2) == 2)
    assert(factorial(3) == 6)
    assert(factorial(4) == 24)
    assert(factorial(5) == 120)

Now let's calculate _combination_, or the polynomial coefficient $C^n_k$. It is trivial also, the formulae is:

$$C^n_k = \frac {n!} {k! (n-k)!} $$

We can simplify it a little, as $ n ≥ k $, we can remove $k!$ from the fraction:

$$C^n_k = \frac {(k+1)(k+1)...n} { (n-k)!} $$

Now the code is much more effective:

    fun C(n,k) {
        var result = k+1
        var ck = result + 1
        while( ck <= n ) result *= ck++
        result / factorial(n-k)
    }

    println(C(10,3))
    assert( C(10, 3) == 120 )

to be continued...


