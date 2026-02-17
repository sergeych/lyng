# Bytecode VM notes

- Opcode switch dispatch was measured slower than virtual dispatch; keep the per-op Cmd class path for now.
