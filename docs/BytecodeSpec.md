# Lyng Bytecode VM Spec v0 (Draft)

This document describes a register-like (3-address) bytecode for Lyng with
dynamic slot width (8/16/32-bit slot IDs), a slot-tail argument model, and
typed lanes for Obj/Int/Real/Bool. The VM is intended to run as a suspendable
interpreter and fall back to the existing AST execution when needed.

## 1) Frame & Slot Model

### Frame metadata
- localCount: number of local slots for this function (fixed at compile time).
- argCount: number of arguments passed at call time.
- argBase = localCount.

### Slot layout
slots[0 .. localCount-1]              locals
slots[localCount .. localCount+argCount-1]  arguments

### Typed lanes
- slotType[]: UNKNOWN/OBJ/INT/REAL/BOOL
- objSlots[], intSlots[], realSlots[], boolSlots[]
- A slot is a logical index; active lane is selected by slotType.

### Parameter access
- param i => slot localCount + i
- variadic extra => slot localCount + declaredParamCount + k

## 2) Slot ID Width

Per frame, select:
- 8-bit if localCount + argCount < 256
- 16-bit if < 65536
- 32-bit otherwise

The decoder uses a dedicated loop per width. All slot operands are expanded to
Int internally.

## 3) CALL Semantics (Model A)

Instruction:
CALL_DIRECT fnId, argBase, argCount, dst

Behavior:
- Allocate a callee frame sized localCount + argCount.
- Copy caller slots [argBase .. argBase+argCount-1] into callee slots
  [localCount .. localCount+argCount-1].
- Callee returns via RET slot or RET_VOID.
- Caller stores return value to dst.

Other calls:
- CALL_VIRTUAL recvSlot, methodId, argBase, argCount, dst
- CALL_FALLBACK stmtId, argBase, argCount, dst

## 4) Binary Encoding Layout

All instructions are:
  [opcode:U8] [operands...]

Operand widths:
- slotId: S = 1/2/4 bytes (per frame slot width)
- constId: K = 2 bytes (U16), extend to 4 if needed
- ip: I = 2 bytes (U16) or 4 bytes (U32) per function size
- fnId/methodId/stmtId: F/M/T = 2 bytes (U16) unless extended
- argCount: C = 2 bytes (U16), extend to 4 if needed

Endianness: little-endian for multi-byte operands.

Common operand patterns:
- S: one slot
- SS: two slots
- SSS: three slots
- K S: constId + dst slot
- S I: slot + jump target
- I: jump target
- F S C S: fnId, argBase slot, argCount, dst slot

## 5) Opcode Table

Note: Any opcode can be compiled to FALLBACK if not implemented in a VM pass.

### Data movement
- NOP
- MOVE_OBJ S -> S
- MOVE_INT S -> S
- MOVE_REAL S -> S
- MOVE_BOOL S -> S
- CONST_OBJ K -> S
- CONST_INT K -> S
- CONST_REAL K -> S
- CONST_BOOL K -> S
- CONST_NULL -> S

### Numeric conversions
- INT_TO_REAL S -> S
- REAL_TO_INT S -> S
- BOOL_TO_INT S -> S
- INT_TO_BOOL S -> S

### Arithmetic: INT
- ADD_INT S, S -> S
- SUB_INT S, S -> S
- MUL_INT S, S -> S
- DIV_INT S, S -> S
- MOD_INT S, S -> S
- NEG_INT S -> S
- INC_INT S
- DEC_INT S

### Arithmetic: REAL
- ADD_REAL S, S -> S
- SUB_REAL S, S -> S
- MUL_REAL S, S -> S
- DIV_REAL S, S -> S
- NEG_REAL S -> S

### Bitwise: INT
- AND_INT S, S -> S
- OR_INT S, S -> S
- XOR_INT S, S -> S
- SHL_INT S, S -> S
- SHR_INT S, S -> S
- USHR_INT S, S -> S
- INV_INT S -> S

### Comparisons (typed)
- CMP_EQ_INT S, S -> S
- CMP_NEQ_INT S, S -> S
- CMP_LT_INT S, S -> S
- CMP_LTE_INT S, S -> S
- CMP_GT_INT S, S -> S
- CMP_GTE_INT S, S -> S
- CMP_EQ_REAL S, S -> S
- CMP_NEQ_REAL S, S -> S
- CMP_LT_REAL S, S -> S
- CMP_LTE_REAL S, S -> S
- CMP_GT_REAL S, S -> S
- CMP_GTE_REAL S, S -> S
- CMP_EQ_BOOL S, S -> S
- CMP_NEQ_BOOL S, S -> S

### Mixed numeric comparisons
- CMP_EQ_INT_REAL S, S -> S
- CMP_EQ_REAL_INT S, S -> S
- CMP_LT_INT_REAL S, S -> S
- CMP_LT_REAL_INT S, S -> S
- CMP_LTE_INT_REAL S, S -> S
- CMP_LTE_REAL_INT S, S -> S
- CMP_GT_INT_REAL S, S -> S
- CMP_GT_REAL_INT S, S -> S
- CMP_GTE_INT_REAL S, S -> S
- CMP_GTE_REAL_INT S, S -> S
- CMP_NEQ_INT_REAL S, S -> S
- CMP_NEQ_REAL_INT S, S -> S
- CMP_EQ_OBJ S, S -> S
- CMP_NEQ_OBJ S, S -> S
- CMP_REF_EQ_OBJ S, S -> S
- CMP_REF_NEQ_OBJ S, S -> S
- CMP_LT_OBJ S, S -> S
- CMP_LTE_OBJ S, S -> S
- CMP_GT_OBJ S, S -> S
- CMP_GTE_OBJ S, S -> S

### Boolean ops
- NOT_BOOL S -> S
- AND_BOOL S, S -> S
- OR_BOOL S, S -> S

### Control flow
- JMP I
- JMP_IF_TRUE S, I
- JMP_IF_FALSE S, I
- RET S
- RET_VOID

### Calls
- CALL_DIRECT F, S, C, S
- CALL_VIRTUAL S, M, S, C, S
- CALL_FALLBACK T, S, C, S

### Object access (optional, later)
- GET_FIELD S, M -> S
- SET_FIELD S, M, S
- GET_INDEX S, S -> S
- SET_INDEX S, S, S

### Fallback
- EVAL_FALLBACK T -> S

## 6) Const Pool Encoding (v0)

Each const entry is encoded as:
  [tag:U8] [payload...]

Tags:
- 0x00: NULL
- 0x01: BOOL (payload: U8 0/1)
- 0x02: INT (payload: S64, little-endian)
- 0x03: REAL (payload: F64, IEEE-754, little-endian)
- 0x04: STRING (payload: U32 length + UTF-8 bytes)
- 0x05: OBJ_REF (payload: U32 index into external Obj table)

Notes:
- OBJ_REF is reserved for embedding prebuilt Obj handles if needed.
- Strings use UTF-8; length is bytes, not chars.

## 7) Function Header (binary container)

Suggested layout for a bytecode function blob:
- magic: U32 ("LYBC")
- version: U16 (0x0001)
- slotWidth: U8 (1,2,4)
- ipWidth: U8 (2,4)
- constIdWidth: U8 (2,4)
- localCount: U32
- codeSize: U32 (bytes)
- constCount: U32
- constPool: [const entries...]
- code: [bytecode...]

Const pool entries use the encoding described in section 6.

## 8) Sample Bytecode (illustrative)

Example Lyng:
  val x = 2
  val y = 3
  val z = x + y

Assume:
- localCount = 3 (x,y,z)
- argCount = 0
- slot width = 1 byte
- const pool: [INT 2, INT 3]

Bytecode:
  CONST_INT k0 -> s0
  CONST_INT k1 -> s1
  ADD_INT s0, s1 -> s2
  RET_VOID

Encoded (opcode values symbolic):
  [OP_CONST_INT][k0][s0]
  [OP_CONST_INT][k1][s1]
  [OP_ADD_INT][s0][s1][s2]
  [OP_RET_VOID]

## 9) Notes

- Mixed-mode is allowed: compiler can emit FALLBACK ops for unsupported nodes.
- The VM must be suspendable; on suspension, store ip + minimal operand state.
- Source mapping uses a separate ip->Pos table, not part of core bytecode.
