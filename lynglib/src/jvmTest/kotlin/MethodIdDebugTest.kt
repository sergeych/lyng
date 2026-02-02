package net.sergeych.lyng

import net.sergeych.lyng.obj.ObjArrayIterator
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjIterator
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjRange
import net.sergeych.lyng.obj.ObjRangeIterator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MethodIdDebugTest {
    @Test
    fun testIterableIteratorMethodIdsPresentOnConcreteTypes() {
        val iterableIds = ObjIterable.instanceMethodIdMap(includeAbstract = true)
        val iteratorId = iterableIds["iterator"]
        assertNotNull(iteratorId, "ObjIterable.iterator methodId missing")
        val listRec = ObjList.type.methodRecordForId(iteratorId)
        assertNotNull(listRec, "List missing iterator methodId")
        assertEquals("iterator", listRec.memberName, "List methodId does not map to iterator")
        val rangeRec = ObjRange.type.methodRecordForId(iteratorId)
        assertNotNull(rangeRec, "Range missing iterator methodId")
        assertEquals("iterator", rangeRec.memberName, "Range methodId does not map to iterator")
    }

    @Test
    fun testIteratorMethodsPresentOnConcreteIterators() {
        val iteratorIds = ObjIterator.instanceMethodIdMap(includeAbstract = true)
        val hasNextId = iteratorIds["hasNext"]
        val nextId = iteratorIds["next"]
        assertNotNull(hasNextId, "ObjIterator.hasNext methodId missing")
        assertNotNull(nextId, "ObjIterator.next methodId missing")
        val arrayHasNext = ObjArrayIterator.type.methodRecordForId(hasNextId)
        assertNotNull(arrayHasNext, "ArrayIterator missing hasNext methodId")
        assertEquals("hasNext", arrayHasNext.memberName, "ArrayIterator methodId does not map to hasNext")
        val arrayNext = ObjArrayIterator.type.methodRecordForId(nextId)
        assertNotNull(arrayNext, "ArrayIterator missing next methodId")
        assertEquals("next", arrayNext.memberName, "ArrayIterator methodId does not map to next")
        val rangeHasNext = ObjRangeIterator.type.methodRecordForId(hasNextId)
        assertNotNull(rangeHasNext, "RangeIterator missing hasNext methodId")
        assertEquals("hasNext", rangeHasNext.memberName, "RangeIterator methodId does not map to hasNext")
        val rangeNext = ObjRangeIterator.type.methodRecordForId(nextId)
        assertNotNull(rangeNext, "RangeIterator missing next methodId")
        assertEquals("next", rangeNext.memberName, "RangeIterator methodId does not map to next")
    }
}
