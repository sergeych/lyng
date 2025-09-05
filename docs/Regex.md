# Regular expressions

In lyng, you create regular expressions using class `Regex` or `String.re` methods:

    assert( "\d*".re is Regex )
    assert( Regex("\d*") is Regex )
    >>> void

We plan to add slash syntax at some point.

To check that some string matches as whole to some regex:

    assert( "123".matches("\d{3}".re) )
    assert( !"123".matches("\d{4}".re) )
    assert( !"1234".matches("\d".re) )
    >>> void

To check that _part of the string_ matches some regular expession, use _match operator_ `=~` just like in Ruby, and its
counterpart, _not match_ operator `!~`:

    assert( "abc123def" =~ "\d\d\d".re )
    assert( "abc" !~ "\d\d\d".re )
    >>> void

When you need to find groups, and more detailed match information, use `Regex.find`:

    val result = Regex("abc(\d)(\d)(\d)").find( "bad456 good abc123")
    assert( result  != null )
    assertEquals( 12 .. 17, result.range )
    assertEquals( "abc123", result[0] )
    assertEquals( "1", result[1] )
    assertEquals( "2", result[2] )
    assertEquals( "3", result[3] )
    >>> void

Note that the object `RegexMatch`, returned by [Regex.find], behaves much like in many other languages: it provides the
index range and groups matches as indexes.

Match operator actually also provides `RegexMatch` in `$~` reserved variable (borrowed from Ruby too):

    assert( "bad456 good abc123" =~ "abc(\d)(\d)(\d)".re )
    assertEquals( 12 .. 17, $~.range )
    assertEquals( "abc123", $~[0] )
    assertEquals( "1", $~[1] )
    assertEquals( "2", $~[2] )
    assertEquals( "3", $~[3] )
    >>> void

This is often more readable than calling `find`.

Note that `=~` and `!~` operators against strings and regular expressions are commutative, e.g. regular expression and a
string can be either left or right operator, but not both:

    assert( "abc" =~ "\wc".re )
    assert( "abc" !~ "\w1c".re )
    assert( "a\wc".re =~ "abcd" )
    assert( "a[a-z]c".re !~ "a2cd" )
    >>> void

Also, string indexing is Regex-aware, and works like `Regex.find` (_not findall!_):

    assert( "cd" == "abcdef"[ "c.".re ].value )
    >>> void


# Regex class reference

| name         | description                         | notes |
|--------------|-------------------------------------|-------|
| matches(str) | true if the whole `str` matches     |       |
| find(str)    | find first match in `str` or null   | (1)   |
| findAll(str) | find all matches in `str` as [List] | (1)   |

(1)
:: See `RegexMatch` class description below

# RegexMatch

| name  | description                               | notes |
|-------|-------------------------------------------|-------|
| range | the [Range] of the match in source string |       |
| value | the value that matches                    |       |
| [n]   | [List] of group matches                   | (1)   |

(1)
:: the [0] element is always value, [1] is group 1 match of any, etc.

[List]: List.md

[Range]: Range.md

