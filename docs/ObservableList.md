# ObservableList module

`ObservableList` lives in explicit module `lyng.observable`.

Import it first:

    import lyng.observable
    >>> void

Create from a regular mutable list:

    import lyng.observable
    val xs = [1,2,3].observable()
    assert(xs is ObservableList<Int>)
    assertEquals([1,2,3], xs)
    >>> void

## Hook flow

Event order is:
1. `beforeChange(change)` listeners
2. mutation commit
3. `onChange(change)` listeners
4. `changes()` flow emission

Rejection is done by throwing in `beforeChange`.

    import lyng.observable
    val xs = [1,2].observable()
    xs.beforeChange {
        throw ChangeRejectionException("no mutation")
    }
    assertThrows(ChangeRejectionException) { xs += 3 }
    assertEquals([1,2], xs)
    >>> void

## Subscriptions

`beforeChange` and `onChange` return `Subscription`.
Call `cancel()` to unsubscribe.

    import lyng.observable
    val xs = [1].observable()
    var hits = 0
    val sub = xs.onChange { hits++ }
    xs += 2
    sub.cancel()
    xs += 3
    assertEquals(1, hits)
    >>> void

## Change events

`changes()` returns `Flow<ListChange<T>>` with concrete event classes:
- `ListInsert`
- `ListSet`
- `ListRemove`
- `ListClear`
- `ListReorder`

    import lyng.observable
    val xs = [10,20].observable()
    val it = xs.changes().iterator()
    xs[1] = 200
    val ev = it.next()
    assert(ev is ListSet<Int>)
    assertEquals(20, (ev as ListSet<Int>).oldValue)
    assertEquals(200, ev.newValue)
    it.cancelIteration()
    >>> void
